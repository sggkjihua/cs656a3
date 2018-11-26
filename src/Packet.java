import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Packet {
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
