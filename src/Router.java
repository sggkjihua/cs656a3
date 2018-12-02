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
    public static int DELAY = 10000;
    public static String fileName ;
    public static String LogContent;
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

        LogContent = String.format("Router %s \n",router_id);
        fileName = String.format("Router(%1$s).log",router_id);
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
        MSG = "Initialized packet sent: router ID "+ router_id+ " waiting for circuit database...\n";
        writeLog(MSG);
        LogContent += MSG;
        System.out.print(MSG);
    }

    public static void sendHello() throws IOException {
        /* send HELLO packet to packet */
        Map<Integer, Integer> myLinks = topology.get(router_id);
        for (Map.Entry<Integer,Integer> entry : myLinks.entrySet()) {
            Packet helloPacket = Packet.gnenerate_Hello(router_id, entry.getKey());
            sendPacket(helloPacket);
            MSG = "Hello packet: ---> RouterID: " + router_id + " and LinkID: " + entry.getKey()+"\n";
            LogContent += MSG;
            writeLog(MSG);
            System.out.print(MSG);
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
        dp_receive.setLength(buff_length);
        initAdjcent();
    }

    public static void receiveHello() throws IOException {
        // mainly to record the id of the neighbours as well as the link between them
        Packet helloPacket = Packet.helloPacket_parser(dp_receive.getData());
        int neighbour_id = helloPacket.getRouter_id();
        int link_id = helloPacket.getLink_id();
        updateTopology(neighbour_id,link_id, topology.get(router_id).get(link_id));
        // initialize the neighbors map
        updateAdjcent(neighbour_id,link_id);
        // once receive the hello packet, update the neighbours
        updateNeighbours(neighbour_id, link_id);
        //System.out.println("The length of the data in Hello Packet is " +dp_receive.getLength());
        MSG = "Neighbour ID is: "+neighbour_id+" Link ID is: "+link_id +"\n";
        System.out.print(MSG);
        writeLog(MSG);
        LogContent += MSG;

        // in the meantime, send the current topology to the recently joined neighbour
        sendTopologyToNeighbour(neighbour_id, link_id);

        MSG = "Current topology successfully sent to : "+neighbour_id+" via "+link_id+"\n";
        System.out.print(MSG);
        writeLog(MSG);
        LogContent += MSG;
        dp_receive.setLength(buff_length);
    }

    public static void receiveLSPDU(){
        /* parse the LSPDU packet and update the cost topology, in the mean time send to unknown neighbour*/
        Packet LsPduPacket = Packet.lsPDU_parser(dp_receive.getData());
        //System.out.println("The length of the data received from lsPDU packet is " +dp_receive.getLength());
        MSG = "LsPDU: <--- " + LsPduPacket.getSender() +" via "+LsPduPacket.getVia()
                + " saying router "+LsPduPacket.getRouter_id()+" has link " + LsPduPacket.getLink_id()+" with cost "+LsPduPacket.getCost()+"\n";
        System.out.print(MSG);
        writeLog(MSG);
        LogContent += MSG;

        updateTopology(LsPduPacket.getRouter_id(), LsPduPacket.getLink_id(), LsPduPacket.getCost());
        updateAdjcent(LsPduPacket.getRouter_id(),LsPduPacket.getLink_id());
        OSPF();
        //System.out.println("Topology updated!");
        forwardPacket(LsPduPacket);
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



    public static void parseMap(Map<Integer,Map<Integer,Integer>> map, String message) {
        for (Map.Entry<Integer, Map<Integer,Integer>> entry : map.entrySet()) {
            int router = entry.getKey();
            System.out.println(message+ router);
            LogContent += message+router+"\n";
            writeLog(message+router+"\n");
            Map<Integer, Integer> costs = entry.getValue();
            for (Map.Entry<Integer,Integer> entry2 : costs.entrySet()) {
                MSG = entry2.getKey()+"  " +entry2.getValue()+"|";
                LogContent += MSG;
                System.out.print(MSG);
                writeLog(MSG);
            }
            System.out.println(" ");
            LogContent +="\n";
            writeLog("\n");
            System.out.println("------------------------");
            writeLog("=====================");
            LogContent += "======================\n";
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
                    MSG = "Current topology: ---> " + neighbour_id + " via " + via
                            + " saying router " + router + " has link " + link + " with cost " + cost+"\n";
                    System.out.print(MSG);
                    writeLog(MSG);
                    LogContent += MSG;

                }
            }
        }
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


    public static void updateTopology(int router_id, int link_id, int cost){
        if(!topology.containsKey(router_id)){
            topology.put(router_id,new HashMap<>());
        }
        topology.get(router_id).put(link_id,cost);
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
                    MSG = "LsPDU: ---> from " + originalSender + " forwarded to " + neighbor + " via " + link
                            + " saying " + LsPduPacket.getRouter_id() + " has link " + LsPduPacket.getLink_id() + " with cost " + LsPduPacket.getCost()+"\n";
                    System.out.print(MSG);
                    writeLog(MSG);
                    LogContent += MSG;
                }else{
                    MSG = "(NOT FORWARDED) "+neighbor+" has already known "+LsPduPacket.getRouter_id()+" has link "+LsPduPacket.getLink_id()+"\n";
                    System.out.print(MSG);
                    writeLog(MSG);
                    LogContent+=MSG;
                }
            }
        }
    }


    public static Map<Integer,Integer> initShortestPath(){
        Map<Integer,Integer> shortestPath = new HashMap<>();
        shortestPath.put(router_id, 0);
        excluded.clear();
        excluded.put(router_id, true);

        for(Map.Entry<Integer,Integer> entry:neighbours.entrySet()){
            int neighbor = entry.getKey();
            int link = entry.getValue();
            int cost = topology.get(router_id).get(link);
            shortestPath.put(neighbor,cost);
        }
        for(Map.Entry<Integer,Map<Integer,Integer>>entry2:topology.entrySet()){
            if(!shortestPath.containsKey(entry2.getKey())){
                shortestPath.put(entry2.getKey(),EXTREMELY_LARGE_NUMBER);
            }
        }
        return shortestPath;
    }

    public static void OSPF(){
        Map<Integer,Integer> shortestPath = initShortestPath();
        while (excluded.size()!=topology.size()){
            int nextNode = findNextNode(shortestPath);
            //System.out.println("Current node is: "+nextNode+" excludes size is: "+excluded.size()+" Topology size is: "+topology.size());
            Map<Integer,Integer> links = topology.get(nextNode);
            for(Map.Entry<Integer,Integer> entry:links.entrySet()){
                int link = entry.getKey();
                int cost = entry.getValue();
                int router_id = adjcent.get(link).get(nextNode);
                if((Integer)router_id != WAIT_TO_BE_SUB){
                    shortestPath.put(router_id,Math.min(shortestPath.get(nextNode)+cost,shortestPath.get(router_id)));
                }
            }
        }
        showShortest(shortestPath);
    }

    public static int findNextNode(Map<Integer,Integer> shortestPath){
        int minimumCost = EXTREMELY_LARGE_NUMBER+1;
        int nextNode = router_id;
        for(Map.Entry<Integer,Integer> entry:shortestPath.entrySet()){
            int router_ID = entry.getKey();
            int cost = entry.getValue();
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


    public static void showShortest(Map<Integer,Integer> map) {
        for (Map.Entry<Integer,Integer> entry : map.entrySet()) {
            int dest = entry.getKey();
            int cost = entry.getValue();
            MSG = "Cost to "+dest+" is "+cost +"\n";
            System.out.print(MSG);
            writeLog(MSG);
            LogContent += MSG;
        }
    }

    static class Boom extends TimerTask{
        @Override
        public void run() {
            parseMap(topology,"Topology of ");
            LogContent += "+++++++++++++++\n";
            writeLog("++++++++++++++\n");
            System.out.println("+++++++++++++++++");
            parseMap(adjcent,"Adjcent pair of link ");
            LogContent += "+++++++++++++++\n";
            writeLog("+++++++++++++++\n");
            System.out.println("+++++++++++++++++");
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

    public static void writeLog(String MSG) {
        try(FileWriter fw = new FileWriter(fileName,true);
            BufferedWriter bw = new BufferedWriter(fw);
            PrintWriter out = new PrintWriter(bw))
        {
            out.println(MSG);
        } catch (IOException e) {
            //exception handling left as an exercise for the reader
        }
    }
}




