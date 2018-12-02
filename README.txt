1. INTRODUCTION
---------------------------------------------------------------------------------------------------------------------------------------
This file simulate the execuation of Client-Server communication.
The UDP server will keep listening to the client and print out the port number where they will negotiate on the TCP connection.
====== Server ========
SERVER_PORT=49603

Client will send the req_code asking for TCP port number and right after UDP server receive the req_code, it will find the available port number and send back:
====== Server ========
SERVER_TCP_PORT=36314
====== Client ========
UDPClient received message from UDPServer:
36314 from 129.97.56.11:49603

Once the client receive the port number from the server, it will send the number back for confirmation:
====== Client ========
UDPClient received message from UDPServer:
OK from 129.97.56.11:49603

Once the client receive OK from server, meaning that the TCP port is usable, it will send the string to the server and get the reversed string in return:
====== Server ========
SERVER_RCV_MSG=Jihua xu Guo xiaolin
====== Client ========
CLIENT_RCV_MSG=niloaix ouG ux auhiJ

The client will then exit automatically while the server will keep listening for other requests


2. COMPILE INSTRUCTIONS
---------------------------------------------------------------------------------------------------------------------------------------
(1) Switch to the folder where the makefile exists
make build ----> compile Client.java Server.java in src, class files locate in bin

(2) Switch to bin floder and run sh file 
cd bin         ----> switch to bin
sh server.sh parameter ----> parameter should be an Integer(e.g. 13)
sh client.sh  parameter1 parameter2 parameter3 parameter4 (host, n_port, req_code, string)