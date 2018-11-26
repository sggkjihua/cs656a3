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
        receiver = new Receiver();
        Thread receive = new Thread(receiver);
        receive.start();
    }


    public static void sendInit() throws IOException {
        Packet initPacket = Packet.generate_INIT(router_id);
        byte[] data = Packet.getUdpData(initPacket);
        DatagramPacket packet = new DatagramPacket(data, data.length, nse_host, nse_port);
        ds.send(packet);
        System.out.println("Initialized packet sent with router ID: "+ router_id);
        System.out.println("Init packet sent waiting for circuit database...");

    }

    public static void sendHello() throws IOException {
        Map<Integer, Integer> myLinks = topology.get(router_id);
        for (Map.Entry<Integer,Integer> entry : myLinks.entrySet()) {
            Packet helloPacket = Packet.gnenerate_Hello(router_id, entry.getKey());
            byte[] data = Packet.getUdpData(helloPacket);
            DatagramPacket packet = new DatagramPacket(data, data.length, nse_host, nse_port);
            ds.send(packet);
            System.out.println("Hello packet sent with RouterID: " + router_id + " and LinkID: " + entry.getKey());
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
                sendLsPdu();
                break;
                // actually 20 for lspdu
                default:
                    receiveLSPDU();
                    if(!updated.isEmpty()){
                        sendLsPdu();
                        updated.clear();
                    }
                    break;
        }
    }

    public static void sendLsPdu() throws IOException{
        for (Map.Entry<Integer, Integer> entry : neighbours.entrySet()) {
            int neighbor= entry.getKey();
            int via = entry.getValue();
            Map<Integer, Integer> link_cost;
            for(Map.Entry<Integer, Map<Integer,Integer>> entry1:updated.entrySet()){
                int ori = entry1.getKey();
                link_cost = entry1.getValue();
                for(Map.Entry<Integer,Integer> entry3:link_cost.entrySet()){
                    int link_id = entry3.getKey();
                    int cost = entry3.getValue();
                    Packet LS_PDU = Packet.generate_LSPDU(router_id,ori,link_id,cost,via);
                    byte[] data = Packet.getUdpData(LS_PDU);
                    DatagramPacket packet = new DatagramPacket(data, data.length, nse_host, nse_port);
                    ds.send(packet);
                    System.out.println("LS_PDU sent to " + neighbor +" via "+ via+
                            " saying Router "+router_id+" has link " + link_id+" with cost "+ cost);
                }
            }

            }
    }

    public static void receiveCircuitDatabase() {
        System.out.println("The length of the data received from circuit database is " +dp_receive.getLength());
        // initialize the topology once receive a circuit database from simulator
        topology = Packet.circuitDB_parser(dp_receive.getData(), router_id);
        dp_receive.setLength(buff_length);
    }

    public static void receiveHello() {
        // mainly to record the id of the neighbours as well as the link between them
        neighbours = Packet.helloPacket_parser(dp_receive.getData(),neighbours);
        System.out.println("The length of the data received from hello packet is " +dp_receive.getLength());
        dp_receive.setLength(buff_length);
    }

    public static void receiveLSPDU() {
        topology = Packet.lsPDU_parser(dp_receive.getData(),topology);
        System.out.println("The length of the data received from lsPDU packet is " +dp_receive.getLength());
        dp_receive.setLength(buff_length);
    }


    public static void parseMap(Map<Integer,List<Integer>> map) {

        for (Map.Entry<Integer, List<Integer>> entry : map.entrySet()) {
            int key = entry.getKey();
            List<Integer> link_list = entry.getValue();
            for (int i = 0; i < link_list.size(); i++) {
                System.out.println("Router ID = " + key + ", has link = " + link_list.get(i));
            }
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



