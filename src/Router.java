import javafx.util.Pair;
import jdk.jfr.Unsigned;

import javax.sound.midi.Receiver;
import javax.xml.crypto.Data;
import java.io.*;
import java.net.CookiePolicy;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

public class Router {
    static int router_id;
    static InetAddress nse_host;
    static int nse_port;
    static int router_port;
    static DatagramSocket ds;
    private static byte[] buf;
    public static final int buff_length = 512;
    private static DatagramPacket dp_receive;
    private static Map<Integer,Integer> neighbours = new HashMap<>();
    private static Map<Integer,Map<Integer,Integer>> topology = new HashMap<>();
    private static Receiver receiver;
    public static Map<Pair<Integer,Integer>, Map<Integer,Boolean>> already_sent = new HashMap<>();
    public static Boom boom;
    public static Map<Integer,Map<Integer, Integer>> adjcent = new HashMap<>();
    public static Map<Integer,Boolean> excluded = new HashMap<>();
    public static int EXTREMELY_LARGE_NUMBER = 100000000;
    public static int WAIT_TO_BE_SUB = 666;
    public static int DELAY = 60000;
    public static String fileName ;
    public static String MSG;

    public static void main(String args[]) throws IOException {
        // get all the input parameters
        router_id = Integer.parseInt(args[0]);
        nse_host = InetAddress.getByName(args[1]);
        nse_port = Integer.parseInt(args[2]);
        router_port = Integer.parseInt(args[3]);

        // initialize the socket and receive buffer
        ds = new DatagramSocket(router_port);
        buf = new byte[buff_length];
        dp_receive = new DatagramPacket(buf, buf.length);

        //LogContent = String.format("Router %s \n",router_id);
        fileName = String.format("router(%1$s).log",router_id);
        // send init packet to emulator
        sendInit();
        // create a thread to receive the data, classifying them with the length of the data
        receiver = new Receiver();
        Thread receive = new Thread(receiver);
        receive.start();
    }


    public static void sendInit() throws IOException {
        /* send INIT packet to simulator */
        Packet initPacket = Packet.generate_INIT(router_id);
        sendPacket(initPacket);
        MSG = "Initialized packet sent: router ID "+ router_id+ " waiting for circuit database...";
        initLog(MSG);
    }

    public static void sendHello() throws IOException {
        /* send HELLO packet to packet */
        Map<Integer, Integer> myLinks = topology.get(router_id);
        for (Map.Entry<Integer,Integer> entry : myLinks.entrySet()) {
            Packet helloPacket = Packet.gnenerate_Hello(router_id, entry.getKey());
            sendPacket(helloPacket);
            MSG = "Hello packet: ---> RouterID: " + router_id + " LinkID: " + entry.getKey();
            writeLog(MSG);
        }
    }

    public static void receive() throws IOException{
        /* deal with the packet received based on the size of the data received */
        ds.receive(dp_receive);
        int packet_length = dp_receive.getLength();
        switch (packet_length){
            case 44:
                receiveCircuitDatabase();
                sendHello();
                boom = new Boom();
                new Timer().schedule(boom,DELAY);
                break;
            case 8:
                receiveHello();
                break;
            default:
                // 20 actually
                receiveLSPDU();
                break;
        }
    }

    public static void receiveCircuitDatabase() {
        //System.out.println("The length of the data received from circuit database is " +dp_receive.getLength());
        // initialize the topology once receive a circuit database from simulator
        topology = Packet.circuitDB_parser(dp_receive.getData(), router_id);
        parseMap(topology,"CircuitDB(Link:Cost): <--- of Router ");
        dp_receive.setLength(buff_length);
        initAdjcent();
    }

    public static void receiveHello() throws IOException {
        // mainly to record the id of the neighbours as well as the link between them
        Packet helloPacket = Packet.helloPacket_parser(dp_receive.getData());
        int neighbour_id = helloPacket.getRouter_id();
        int link_id = helloPacket.getLink_id();
        if(updateTopology(neighbour_id,link_id, topology.get(router_id).get(link_id))){
            System.out.println("New neighbor discovered: "+neighbour_id);
        }
        // initialize the neighbors map
        updateAdjcent(neighbour_id,link_id);
        // once receive the hello packet, update the neighbours
        updateNeighbours(neighbour_id, link_id);
        //System.out.println("The length of the data in Hello Packet is " +dp_receive.getLength());
        MSG = "\nHello packet: <--- from Neighbour "+neighbour_id+" via Link "+link_id;
        writeLog(MSG);

        // in the meantime, send the current topology to the recently joined neighbour
        sendTopologyToNeighbour(neighbour_id, link_id);
        dp_receive.setLength(buff_length);
        parseMap(topology,"Current Topology of ");
        OSPF();
    }

    public static void receiveLSPDU(){
        /* parse the LSPDU packet and update the cost topology, in the mean time send to unknown neighbour*/
        Packet LsPduPacket = Packet.lsPDU_parser(dp_receive.getData());
        //System.out.println("The length of the data received from lsPDU packet is " +dp_receive.getLength());
        MSG = "LS_PDU: <--- " + LsPduPacket.getSender() +" via "+LsPduPacket.getVia()
                + " saying router "+LsPduPacket.getRouter_id()+" has link " + LsPduPacket.getLink_id()+" with cost "+LsPduPacket.getCost();
        writeLog(MSG);
        forwardPacket(LsPduPacket);
        if(updateTopology(LsPduPacket.getRouter_id(), LsPduPacket.getLink_id(), LsPduPacket.getCost())){
            updateAdjcent(LsPduPacket.getRouter_id(),LsPduPacket.getLink_id());
            parseMap(topology,"Current Topology of ");
            OSPF();
        }
        //System.out.println("Topology updated!");
        dp_receive.setLength(buff_length);
    }

    public static void initAdjcent(){
        Map<Integer,Integer> initLinks = topology.get(router_id);
        for(Map.Entry<Integer,Integer> entry1:initLinks.entrySet()){
            int link = entry1.getKey();
            updateAdjcent(router_id,link);
        }
    }

    public static void updateAdjcent(int router_id, int link_id){
        if(!adjcent.containsKey(link_id)){
            adjcent.put(link_id,new HashMap<>());
        }

        if(adjcent.get(link_id).containsKey(router_id)){
            return;
        }
        else if(adjcent.get(link_id).isEmpty()){
           adjcent.get(link_id).put(router_id,WAIT_TO_BE_SUB);
        }
        else{
            int key = (int)adjcent.get(link_id).keySet().toArray()[0];
            adjcent.get(link_id).replace(key,router_id);
            adjcent.get(link_id).put(router_id,key);
        }
    }


    public static void updateNeighbours(int neighbour_id, int link_id){
        /* put the newly found neighbour to the map*/
        neighbours.put(neighbour_id,link_id);
    }


    public static void sendTopologyToNeighbour(int neighbour_id, int via) throws IOException{
        /* send the current topology to the newly found neighbour*/
        for (Map.Entry<Integer, Map<Integer,Integer>> entry : topology.entrySet()) {
            int router = entry.getKey();
            Map<Integer, Integer> link_cost = entry.getValue();
            for (Map.Entry<Integer, Integer> costs : link_cost.entrySet()) {
                int link = costs.getKey();
                int cost = costs.getValue();
                if (!alreadySent(neighbour_id, link, router_id)) {
                    Packet LsPduPacket = Packet.generate_LSPDU(router_id, router, link, cost, via);
                    sendPacket(LsPduPacket);
                    MSG = "Current Topology: ---> " + neighbour_id + " via " + via
                            + " Router " + router + " has Link " + link + " with Cost " + cost;
                    writeLog(MSG);
                }
            }
        }
        MSG = "Current Topology successfully sent to : "+neighbour_id+" via "+via;
        writeLog(MSG);
    }

    public static void sendPacket(Packet packetToSend){
        try{
            byte[] data = Packet.getUdpData(packetToSend);
            DatagramPacket packet = new DatagramPacket(data, data.length, nse_host, nse_port);
            ds.send(packet);
        }catch (IOException e){
            e.printStackTrace();
        }
    }

    public static Boolean alreadySent(int router_id, int link_id, int link_owner){
        Pair<Integer,Integer>  link_inform_router = new Pair<>(router_id,link_id);
        if(router_id == link_owner){
            return true;
        }
        if (!already_sent.containsKey(link_inform_router)){
            already_sent.put(link_inform_router,new HashMap<>());
            return false;
        }
        if(! already_sent.get(link_inform_router).containsKey(link_owner)){
            already_sent.get(link_inform_router).put(link_owner,true);
            return false;
        }
        return true;
    }


    public static Boolean updateTopology(int router_id, int link_id, int cost){
        Boolean updated = false;
        if(!topology.containsKey(router_id)){
            topology.put(router_id,new HashMap<>());
            updated = true;
        }
        if(!topology.get(router_id).containsKey(link_id)){
            updated = true;
        }
        topology.get(router_id).put(link_id,cost);
        return updated;
    }
    public static void forwardPacket(Packet LsPduPacket){
        int originalSender = LsPduPacket.getSender();
        for(Map.Entry<Integer,Integer> entry:neighbours.entrySet()){
            int neighbor = entry.getKey();
            if(!(neighbor==originalSender)){
                int link = entry.getValue();
                if(!alreadySent(neighbor,LsPduPacket.getLink_id(),LsPduPacket.getRouter_id())){
                    LsPduPacket.setSender(router_id);
                    LsPduPacket.setVia(link);
                    sendPacket(LsPduPacket);
                    MSG = "LS_PDU: ---> from " + originalSender + " forwarded to " + neighbor + " via " + link
                            + " saying Router " + LsPduPacket.getRouter_id() + " has Link " + LsPduPacket.getLink_id() + " with Cost " + LsPduPacket.getCost();
                    writeLog(MSG);
                }else{
                    MSG = "(NOT FORWARDED) Neighbor "+neighbor+" has already known "+LsPduPacket.getRouter_id()+" has link "+LsPduPacket.getLink_id();
                    writeLog(MSG);
                }
            }
        }
    }


    public static Map<Integer,Pair<Integer, String>> initShortestPath(){
        Map<Integer,Pair<Integer, String>> shortestPath = new HashMap<>();
        shortestPath.put(router_id, new Pair<>(0,"local"));
        excluded.clear();
        excluded.put(router_id, true);

        for(Map.Entry<Integer,Integer> entry:neighbours.entrySet()){
            int neighbor = entry.getKey();
            int link = entry.getValue();
            int cost = topology.get(router_id).get(link);
            shortestPath.put(neighbor,new Pair<>(cost,Integer.toString(neighbor)));
        }
        for(Map.Entry<Integer,Map<Integer,Integer>>entry2:topology.entrySet()){
            if(!shortestPath.containsKey(entry2.getKey())){
                shortestPath.put(entry2.getKey(),new Pair<>(EXTREMELY_LARGE_NUMBER, "Unreachable"));
            }
        }
        return shortestPath;
    }

    public static void OSPF(){
        Map<Integer,Pair<Integer,String>> shortestPath = initShortestPath();
        while (excluded.size()!=topology.size()){
            int nextNode = findNextNode(shortestPath);
            //System.out.println("Current node is: "+nextNode+" excludes size is: "+excluded.size()+" Topology size is: "+topology.size());
            Map<Integer,Integer> links = topology.get(nextNode);
            for(Map.Entry<Integer,Integer> entry:links.entrySet()){
                int link = entry.getKey();
                int cost = entry.getValue();
                int router_id = adjcent.get(link).get(nextNode);
                if((Integer)router_id != WAIT_TO_BE_SUB){
                    int current_cost = shortestPath.get(router_id).getKey();
                    int through_cost = shortestPath.get(nextNode).getKey() + cost;
                    if (through_cost<current_cost){
                        shortestPath.put(router_id, new Pair<>(through_cost,shortestPath.get(nextNode).getValue()+"->"+Integer.toString(router_id)));
                    }
                }
            }
        }
        showShortest(shortestPath);
    }

    public static int findNextNode(Map<Integer,Pair<Integer,String>> shortestPath){
        int minimumCost = EXTREMELY_LARGE_NUMBER+1;
        int nextNode = router_id;
        for(Map.Entry<Integer,Pair<Integer,String>> entry:shortestPath.entrySet()){
            int router_ID = entry.getKey();
            Pair<Integer,String> cost_path = entry.getValue();
            int cost = cost_path.getKey();
            String path = cost_path.getValue();
            if(!excluded.containsKey(router_ID)){
                if(cost < minimumCost){
                    minimumCost = cost;
                    nextNode = router_ID;
                }
            }
        }
        excluded.put(nextNode,true);
        return nextNode;
    }


    public static void showShortest(Map<Integer,Pair<Integer,String>> rib) {
        MSG = "Current RIB of Router "+router_id+"\n";
        for (Map.Entry<Integer,Pair<Integer,String>> entry : rib.entrySet()) {
            int dest = entry.getKey();
            Pair cost_path = entry.getValue();
            MSG += "Dest: "+dest+ " Cost: "+cost_path.getKey()+" Path: "+cost_path.getValue()+"\n" ;
        }
        writeLog(MSG);

    }

    static class Boom extends TimerTask{
        @Override
        public void run() {
            parseMap(topology,"Topology of ");
            parseMap(adjcent,"Adjacent pair of link ");
            OSPF();
            //writeLog(LogContent);
            System.exit(0);
        }
    }
    static class Receiver implements Runnable{
        @Override
        public void run(){
            while(true){
                try{
                    receive();
                }catch (IOException e){
                    e.printStackTrace();
                }
            }
        }
    }


    public static void parseMap(Map<Integer,Map<Integer,Integer>> map, String message) {
        MSG = "\n";
        for (Map.Entry<Integer, Map<Integer,Integer>> entry : map.entrySet()) {
            int router = entry.getKey();
            MSG += message+router+"\n";
            Map<Integer, Integer> costs = entry.getValue();
            for (Map.Entry<Integer,Integer> entry2 : costs.entrySet()) {
                MSG += entry2.getKey()+"  " +entry2.getValue()+"|";
            }
            MSG +="\n";
        }
        writeLog(MSG);
    }

    public static void writeLog(String MSG) {
        try(FileWriter fw = new FileWriter(fileName,true);
            BufferedWriter bw = new BufferedWriter(fw);
            PrintWriter out = new PrintWriter(bw))
        {
            out.println(MSG);
            System.out.println(MSG);
        } catch (IOException e) {
            //exception handling left as an exercise for the reader
        }
    }

    public static void initLog(String MSG) {
        try(FileWriter fw = new FileWriter(fileName);
            BufferedWriter bw = new BufferedWriter(fw);
            PrintWriter out = new PrintWriter(bw))
        {
            out.println(MSG);
            System.out.println(MSG);
        } catch (IOException e) {
            //exception handling left as an exercise for the reader
        }
    }
}



class Packet {
    static private int length;
    private int router_id;
    private int link_id;
    private int cost;

    public void setSender(int sender) {
        this.sender = sender;
    }

    public void setVia(int via) {
        this.via = via;
    }

    private int sender;
    private int via;
    private static Map<Integer,Integer> circuitDatabase;

    public static Boolean isTopology_updated() {
        return topology_updated;
    }

    private static Boolean topology_updated = false;

    public int getLength() {
        return length;
    }

    public int getRouter_id() {
        return router_id;
    }

    public int getLink_id() {
        return link_id;
    }

    public int getCost() {
        return cost;
    }

    public int getSender() {
        return sender;
    }

    public int getVia() {
        return via;
    }

    public Packet( int sender, int router_id, int link_id, int cost,int via, int length) {
        this.router_id = router_id;
        this.link_id = link_id;
        this.cost = cost;
        this.sender = sender;
        this.via = via;
        this.length = length;
    }


    public static Packet gnenerate_Hello(int router_ID, int link_ID) throws IOException {
        return new Packet(-1,router_ID, link_ID,-1,-1,2);
    }

    public static Packet generate_LSPDU(int sender, int router_id, int link_id, int cost, int via) throws IOException {
        return new Packet(sender, router_id, link_id, cost, via, 5);
    }

    public static Packet generate_INIT(int router_id) throws IOException {
        return new Packet(-1,router_id,-1,-1,-1,1);
    }

    public static byte[] ToBytes(int[] values, int length) throws IOException
        /*transform the int array into bytes*/ {
        ByteBuffer buffer = ByteBuffer.allocate(4 * length);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < values.length; i++) {
            buffer.putInt(values[i]);
        }
        return buffer.array();
    }
    public static byte[] getUdpData(Packet packet)throws IOException{
        int length = packet.getLength();
        switch (length){
            case 1:
                return ToBytes(new int[]{packet.getRouter_id()},length);
            case 2:
                return ToBytes(new int[]{packet.getRouter_id(),packet.getLink_id()},length);
            case 5:
                return ToBytes(new int[]{packet.getSender(),packet.getRouter_id(),packet.getLink_id(),
                        packet.getCost(),packet.getVia()},length);
        }
        return ToBytes(new int[]{},0);
    }


    public static Map<Integer,Map<Integer,Integer>> circuitDB_parser(byte[] cdb, int router_id) {
        // initialize the topology put only its own information
        Map<Integer, Map<Integer,Integer>> topology = new HashMap<>();
        ByteBuffer buffer = ByteBuffer.wrap(cdb);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        topology.put(router_id,new HashMap<>());
        int nbrLink = buffer.getInt();
        for (int i = 1; i < nbrLink + 1; i++) {
            int l = buffer.getInt();
            int c = buffer.getInt();
            topology.get(router_id).put(l,c);
        }
        return topology;
    }

    public static Packet lsPDU_parser(byte[] pdudb) {
        ByteBuffer buffer = ByteBuffer.wrap(pdudb);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int sender = buffer.getInt();
        int router_id = buffer.getInt();
        int link_id = buffer.getInt();
        int cost = buffer.getInt();
        int via = buffer.getInt();
        return new Packet(sender,router_id,link_id,cost,via,5);
    }

    public static Packet helloPacket_parser(byte[] hdb) {
        ByteBuffer buffer = ByteBuffer.wrap(hdb);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int neighbor_ID = buffer.getInt();
        int link_ID = buffer.getInt();
        return new Packet(-1,neighbor_ID, link_ID,-1,-1,2);
    }
}


