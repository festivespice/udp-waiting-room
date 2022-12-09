package org.project;


import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;

/*
The purpose of this class is to allow the server to communicate with a client that has "connected" with the server.
The communication is just pinging the client, and ensuring that they can still be known as a part of the waiting room on the server.
When the waiting room is full, it signals to each RoomClientHandler object that theh game will soon start. At this point, the server
could start the game using the UDP sockets already initialized or create TCP connections with the clients in the satisfied room.

1) Each client connected to the server is represented by a RoomClientHandler object on the server. It implements Runnable,
because it will need to be run simultaneously with other RoomClientHandler threads from the Room it belongs to.

2) Until the room is satisfied and sends a signal to each child RoomClientHandler thread, each thread will send a ping message to the
client connected to the server.

3) Each ConnectedChild server object will ping the client using a timeout mechanism in the UDP socket, mimicking a connection.
If the client doesn't respond to the server's pings, then the client is eventually assumed to be dead and is removed from the
list of "connected" clients.

4) When the room is satisfied, the pinging ends and the server notifies the client that a game will be starting. From here, and implementation
of the server-client interactions for the game can be made.

 */
public class RoomClientHandler implements Runnable {
    Room roomOfClient;
    DatagramSocket uniqueServerSocket;
    InetAddress clientAddress;
    int serverClientPort;
    int clientPort;

    boolean roomSatisfied = false;

    public RoomClientHandler(Room room, DatagramSocket sSocket, InetAddress cIp, int sPort, int cPort){
        this.roomOfClient = room;
        this.uniqueServerSocket = sSocket;
        this.clientAddress = cIp;
        this.serverClientPort = sPort;
        this.clientPort = cPort;
    }

    public void run(){
        //add client to list in the associated room.
        roomOfClient.addClient(this);

        String msgFromClient = "";
        String msgToClient = "";
        byte[] receiveBuffer = new byte[1024];
        byte[] sendBuffer = new byte[0];
        DatagramPacket dgPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
        int currentTime = 4000;
        int consecutiveTimeouts = 0;
        int maxTimeouts = 3;

        //Used to find how much more time we should wait after getting a ping response before sending another.
        //Time should be: time waiting for response + time left until next response
        Instant start;
        Instant end;
        long timeElapsedInMS;
        long timeLeftInMS;

        boolean connected = true;


        while(connected){ //if the room is satisfied (maximum players achieved), we break the loop while connected = true.
            try{
                if(!roomSatisfied){
                    msgToClient = "ping";
                }else{//if the Room changes the value when the number of players needed is met:
                    msgToClient = "done";
                    sendString(uniqueServerSocket, dgPacket, sendBuffer, msgToClient, clientAddress, clientPort); //sends even if there is no response
                    break;
                }
                sendString(uniqueServerSocket, dgPacket, sendBuffer, msgToClient, clientAddress, clientPort); //sends even if there is no response

                uniqueServerSocket.setSoTimeout(currentTime);
                start = Instant.now();
                try{
                    if((msgFromClient=receiveString(uniqueServerSocket, dgPacket, receiveBuffer)) == null || msgFromClient.equals("-1")){
                        //the client isn't connected anymore. Disconnect.
                        connected = false;
                        continue;
                    }
                    end = Instant.now();
                    timeElapsedInMS = (Duration.between(start, end)).toMillis();
                    timeLeftInMS = currentTime - timeElapsedInMS; //how much longer should we wait before sending another ping?
                    Thread.sleep(timeLeftInMS);
                }catch(SocketTimeoutException e){
                    consecutiveTimeouts++;
                    if(consecutiveTimeouts >= maxTimeouts){
                        connected = false;
                        break;
                    }
                    //no response was received. Try (maxTimeouts - consecutiveTimeouts) more times
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    connected = false;
                }
            } catch (IOException e) {
                if(!e.getMessage().equals("Socket closed")){
                    e.printStackTrace();
                }
                connected = false;
            }
        }

        if(connected){
            //TODO: CONTINUE GAME
            System.out.println("Starting game");
        }else{
            msgToClient = "-1";
            try {
                sendString(uniqueServerSocket, dgPacket, sendBuffer, msgToClient, clientAddress, clientPort);
            } catch (IOException e) {
                if(!e.getMessage().equals("Socket closed")){
                    e.printStackTrace();
                }
            }
        }
        roomOfClient.removeClient(this);
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

    public void roomIsSatisifed(){
        roomSatisfied = true;
    }
}
