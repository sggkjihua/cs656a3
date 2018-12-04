1. INTRODUCTION
---------------------------------------------------------------------------------------------------------------------------------------
This file simulate the Shortest Path Routing Algorithm
====== Network Simulator ========
./nselinux386 129.97.167.47 9000

On receive the Init Packet: Network Simulator will send the initialized circuit database telling the related router its links and costs
On receive the Hello Packet: Network Simulator will forward the packet to related(neighbor) router of the sender via the specific link
On receive the LS_PDU Packet: Network Simulator simply forward the packet to neighbor

======== Router ==========
java Router 1 129.97.167.26 9000 9001
java Router 2 129.97.167.26 9000 9002
java Router 3 129.97.167.26 9000 9003
java Router 4 129.97.167.26 9000 9004
java Router 5 129.97.167.26 9000 9005


To initialize: router will send an Init Packet to Network Simulator which will then return the circuit database as the initialized topology
To be discovered: router will send a Hello Packet to Network Simulator which will then be forwarded to its neighbors
To update the topology and calculate the RIB: when receive the LS_PDU from neighbors 

2. OUTPUT STRUCTER
---------------------------------------------------------------------------------------------------------------------------------------
Topology of Router 1
1 1 | 5 5 (Link_ID, Link_Cost) Pair

RIB of router 1 
2 1 (Dest, Cost) Pair

Adjacent Pair of link 1(Key:nodeID, Vlaue:neighborID) for each link
2:1, 1:2 (Node1: Node2), (Node2: Node1)


3. COMPILE INSTRUCTIONS
---------------------------------------------------------------------------------------------------------------------------------------
(1) Switch to the folder where the makefile exists
make build ----> compile Router.java Packet.java in src, class files locate in bin

(2) Switch to bin floder and run sh file 
cd bin         ----> switch to bin
./nselinux386 <host_Y> <port_id_nse>                      ------> Run the Network Simulator

Router 1 <host_X> <port_id_nse> <port_id_router_1>        ------> Run Router from 1 to 5 in order(Must be in order)
Router 2 <host_X> <port_id_nse> <port_id_router_2> 
Router 3 <host_X> <port_id_nse> <port_id_router_3> 
Router 4 <host_X> <port_id_nse> <port_id_router_4> 
Router 5 <host_X> <port_id_nse> <port_id_router_5>

Time for the program to terminate automatically after the Network Simulater has sent the circuit DB: 60000ms(1 min) 
Replace DELAY for a larger number if you want it to wait longer time

