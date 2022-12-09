package org.project;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
The purpose of the Room object is to facilitate one of possibly many waiting rooms hosted by the RoomServer object. The Room object
implements Runnable so that it can be run as a thread.

1) After receiving the port of a Client who sent a request to the RoomServer object over the network, the RoomServer object will
send the port of the client to the specific Room that the client wanted to connect to. If the Room is full, the client won't be able to join
the waiting list. The specific game that will be played in a Room or how many players can join are parameters that are decided when the Room object
was created in the RoomServer object.

2) For each client that "connects" to or joins the waiting list, a RoomClientHandler object is created. This object implements Runnable, so that the
machine will be able to do multithreading to handle multiple clients for a single room at the same time. A port is given to each RoomClientHandler object
at the same time so that channels of communication between the client and the server are exclusive and easy to parse. However, it has the potential of consuming
many UDP ports.

3) When the maximum number of players is reached, the thread pool sends a signal to each RoomClientHandler object. The signal notifies eventually notifies
each client from the server that the game is about to start. At this point, the server could upgrade to a TCP connection if necessary. This sort of connectionless
waiting room can be used to avoid certain DoS attacks that a TCP waiting room might be suspect to.

 */
public class Room implements Runnable{
    RoomServer roomServer;
    DatagramSocket serverSocket;
    int maxClientThreads;
    int serverPort;
    ArrayList<Integer> allPorts = new ArrayList<Integer>();
    ArrayList<Integer> usedPorts = new ArrayList<Integer>();
    ArrayList<Integer> connectedClientPorts = new ArrayList<Integer>();
    String serverName;
    String serverGame;
    String serverIp;
    ArrayList<RoomClientHandler> clients = new ArrayList<RoomClientHandler>();



    //Each room handles a maximum number of threads. Each room should have its own service executor.
    public Room(RoomServer roomServer, DatagramSocket sSocket, String sIp, String sGame, String sName, int maxThreads, int sPort){
        this.roomServer = roomServer;
        this.serverSocket = sSocket;
        this.serverIp = sIp;
        this.serverGame = sGame;
        this.serverName = sName;
        this.maxClientThreads = maxThreads;
        this.serverPort = sPort;
    }
    /* Hosting a room

        The first thread listens for incoming packets. It will add new IPs to an arraylist of Clients
        that each have their own IP/port. Every time a client is added or removed,
        check if the length of the Clients list is the number of max clients. If it is, then notify() the second thread.

        The second thread will check if the number of clients is equal to the maximum number of Client threads.
        It will wait at a wait() block using the condition mentioned.

        The third thread (set of threads): Create a new thread for each client. In this thread, ping the client every 5 seconds. If the client doesn't
        respond within 5 seconds, tell the client that they are no longer connected. Terminate this thread and remove
        the client from the list of Clients. Every time a client is added or removed,
        check if the length of the Clients list is the number of max clients.
    */
    //How do I share data between parent threads and child threads? Pass 'this' object to the constructor of the child runnable.
    public void run(){
        //add the current room to the room server's list of rooms
        initializeRoom();
        ExecutorService clientThreads = Executors.newFixedThreadPool(maxClientThreads);

        byte[] receiveBuffer = new byte[1024];
        byte[] sendBuffer = new byte[0];
        DatagramPacket dgPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);

        waitForClients(dgPacket, receiveBuffer, sendBuffer, clientThreads);
        System.out.println("=== "+serverName+": all players connected");
        try {
            Thread.sleep(8000); //Make sure that all clients have the time to receive the message
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        startGame(); //TODO: implement game

        clientThreads.shutdown();
        roomServer.removeRoom(this);
    }

    //clients.size() in the loop condition is called before clients is actually updated. Force the loop to wait until the client is added. We can synchronize the remove, add, and waitForClients functions
    public synchronized void waitForClients(DatagramPacket dgPacket, byte[] receiveBuffer, byte[] sendBuffer, ExecutorService clientThreads){
        String msgFromClient = "";
        String msgToClient = "";

        int clientPort;
        InetAddress clientAddress;

        try{
            while(clients.size() < maxClientThreads){ //our threadpool isn't full
                msgFromClient = receiveString(serverSocket, dgPacket, receiveBuffer);
                clientPort = dgPacket.getPort();
                clientAddress = dgPacket.getAddress();

                if(msgFromClient.equals("connect")){
                    if(!connectedClientPorts.contains(clientPort)){ //client is a new client if a connection isn't already made for their port
                        int newSocketPort = getPort(); //we'll create a new socket on this process using an unused, available port.
                        DatagramSocket socket = new DatagramSocket(newSocketPort);

                        RoomClientHandler cClient = new RoomClientHandler(this, socket, clientAddress, newSocketPort, clientPort); //we add clientPort to track which client is associated with which server client.
                        clientThreads.submit(cClient);

                        msgToClient = String.valueOf(newSocketPort); //send the client the port of the serverClient that they will connect to for the game of the room it belongs to.
                        sendString(serverSocket,dgPacket,sendBuffer,msgToClient,clientAddress, clientPort);
                    }
                    else{ //if the client tries to connect again, just send '0'...
                        msgToClient = "0";
                        sendString(serverSocket,dgPacket,sendBuffer,msgToClient,clientAddress, clientPort);
                    }
                } else {
                    msgToClient = "Your client message " + msgFromClient + " isn't a known command to a room.";
                    sendString(serverSocket,dgPacket,sendBuffer,msgToClient,clientAddress, clientPort);
                }
                wait();
            }
            //Whenever the threads finish, make sure they're removed from the thread pool.
        }catch(IOException e){
            e.getMessage();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public int getPort(){
        for(int port: allPorts){
            if(!usedPorts.contains(port)){
                usedPorts.add(port);
                return port;
            }
        }
        return 0;
    }

    public void sendString(DatagramSocket socket, DatagramPacket dgPacket, byte[] sendBuffer, String msg, InetAddress clientAddress, int clientPort) throws IOException {
        sendBuffer = msg.getBytes();
        dgPacket = new DatagramPacket(sendBuffer, sendBuffer.length, clientAddress, clientPort);
        socket.send(dgPacket);
    }
    public String receiveString(DatagramSocket socket, DatagramPacket dgPacket, byte[] receiveBuffer) throws IOException{
        socket.receive(dgPacket);
        String receivedData = new String(dgPacket.getData(), 0, dgPacket.getLength());
        return receivedData;
    }

    //Two clients shouldn't be added at the same time
    public synchronized void addClient(RoomClientHandler cl){
        if(!clients.contains(cl)){
            clients.add(cl);
            connectedClientPorts.add(cl.clientPort);
            notify(); //the waitingClients loop is waiting for an accurate value for clients.size()
            printRoomStatus();
        }
    }

    public synchronized void removeClient(RoomClientHandler cl){
        //the serverClient's port is no longer in use, so free it from our list of used ports.
        try{
            usedPorts.remove(Integer.valueOf(cl.serverClientPort)); //If I just input an 'int', it'll search for the index instead of the object.
            //close the serverClient's socket
            cl.uniqueServerSocket.close();

            //remove the connected client's port from the list of connected clients
            connectedClientPorts.remove(Integer.valueOf(cl.clientPort));
            //then remove the client object from the list of clients.
            clients.remove(cl);
            notify(); //the waitingClients loop is waiting for an accurate value for clients.size()
            printRoomStatus();
        }catch(Exception e){
            e.printStackTrace();
        }
    }

    public void printRoomStatus(){
        System.out.println("=== " + serverName);
        for(int i = 0; i < maxClientThreads; i++){
            if((i+1) <= clients.size()){ //if there is an i'th client
                System.out.println("Client connected at port " + clients.get(i).serverClientPort + ",");
            }else{
                System.out.println("\t-");
            }
        }
    }

    public void initializeRoom(){
        roomServer.addRoom(this);
        System.out.println("Room '" +serverName + "' added.");

        //define ports after the server ports that clients can contact
        for(int i = 1; i <= maxClientThreads; i++){
            allPorts.add(serverPort + i); //for example: 5 ports after given server port 33400: 34001...34006
        }
    }

    public void startGame(){
        for(int i = 0; i < clients.size(); i++){
            clients.get(i).roomIsSatisifed();
        }
    }
}
