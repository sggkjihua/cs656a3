import javafx.util.Pair;
import jdk.jfr.Unsigned;

import javax.sound.midi.Receiver;
import javax.xml.crypto.Data;
import java.io.*;
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
    public static Map<Integer,Map<Integer,Integer>> updated = new HashMap<>();
    private static Receiver receiver;
    public static Map<Pair<Integer,Integer>, Map<Integer,Boolean>> already_sent = new HashMap<>();
    public static Boom boom;


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

        // send init packet to emulator
        sendInit();

        // create a thread to receive the data, classifying them with the length of the data
        boom = new Boom();
        new Timer().schedule(boom,30000);
        receiver = new Receiver();
        Thread receive = new Thread(receiver);
        receive.start();
    }


    public static void sendInit() throws IOException {
        Packet initPacket = Packet.generate_INIT(router_id);
        sendPacket(initPacket);
        System.out.println("Initialized packet sent with router ID: "+ router_id);
        System.out.println("Init packet sent waiting for circuit database...");

    }

    public static void sendHello() throws IOException {
        Map<Integer, Integer> myLinks = topology.get(router_id);
        for (Map.Entry<Integer,Integer> entry : myLinks.entrySet()) {
            Packet helloPacket = Packet.gnenerate_Hello(router_id, entry.getKey());
            sendPacket(helloPacket);
            System.out.println("Hello packet: ---> RouterID: " + router_id + " and LinkID: " + entry.getKey());
        }
    }

    public static void receive() throws IOException{
        ds.receive(dp_receive);
        int packet_length = dp_receive.getLength();
        switch (packet_length){
            case 44:
                receiveCircuitDatabase();
                sendHello();
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
    }

    public static void receiveHello() throws IOException {
        // mainly to record the id of the neighbours as well as the link between them
        Packet helloPacket = Packet.helloPacket_parser(dp_receive.getData());
        int neighbour_id = helloPacket.getRouter_id();
        int link_id = helloPacket.getLink_id();

        // once receive the hello packet, update the neighbours
        updateNeighbours(neighbour_id, link_id);
        //System.out.println("The length of the data in Hello Packet is " +dp_receive.getLength());
        System.out.println("Neighbour ID is: "+neighbour_id+" Link ID is: "+link_id);

        // in the meantime, send the current topology to the recently joined neighbour
        sendTopologyToNeighbour(neighbour_id, link_id);
        System.out.println("Current topology successfully sent to : "+neighbour_id+" via "+link_id);
        dp_receive.setLength(buff_length);
    }

    public static void receiveLSPDU(){
        /* parse the LSPDU packet and update the cost topology, in the mean time send to unknown neighbour*/
        Packet LsPduPacket = Packet.lsPDU_parser(dp_receive.getData());
        //System.out.println("The length of the data received from lsPDU packet is " +dp_receive.getLength());
        System.out.println("LsPDU: <--- " + LsPduPacket.getSender() +" via "+LsPduPacket.getVia()
                + " saying router "+LsPduPacket.getRouter_id()+" has link " + LsPduPacket.getLink_id()+" with cost "+LsPduPacket.getCost());
        updateTopology(LsPduPacket.getRouter_id(), LsPduPacket.getLink_id(), LsPduPacket.getCost());
        //System.out.println("Topology updated!");
        forwardPacket(LsPduPacket);
        dp_receive.setLength(buff_length);
    }


    public static void parseMap(Map<Integer,Map<Integer,Integer>> map) {

        for (Map.Entry<Integer, Map<Integer,Integer>> entry : map.entrySet()) {
            int router = entry.getKey();
            System.out.println("Topology of "+ router);
            Map<Integer, Integer> costs = entry.getValue();
            for (Map.Entry<Integer,Integer> entry2 : costs.entrySet()) {
                System.out.print(entry2.getKey()+"  " +entry2.getValue()+"|");
            }
            System.out.println(" ");
            System.out.println("------------------------");
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
                    System.out.println("Current topology: ---> " + neighbour_id + " via " + via
                            + " saying router " + router + " has link " + link + " with cost " + cost);
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
                    System.out.println("LsPDU: ---> from " + originalSender + " forwarded to " + neighbor + " via " + link
                            + " saying " + LsPduPacket.getRouter_id() + " has link " + LsPduPacket.getLink_id() + " with cost " + LsPduPacket.getCost());
                }else{
                    System.out.println("(NOT FORWARDED) "+neighbor+" has already known "+LsPduPacket.getRouter_id()+" has link "+LsPduPacket.getLink_id());
                }
            }
        }
    }


    static class Boom extends TimerTask{
        @Override
        public void run() {
            parseMap(topology);
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
}


class LogWriter {
    public void writeFile(String fileName, ArrayList<Integer> SeqNums) throws IOException {
        File fout = new File(fileName);
        FileOutputStream fos = new FileOutputStream(fout);
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(fos));

        for (int i = 0; i < SeqNums.size(); i++) {
            bw.write(SeqNums.get(i).toString());
            bw.newLine();
        }
        bw.close();
    }
}



