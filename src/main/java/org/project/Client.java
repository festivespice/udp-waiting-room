package org.project;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Scanner;

/*
To use this project, you must first run the RoomServer main and create a room. Then, you can create an instance of Client
and connect to a room using that. You can create multiple RoomServerClients. Honestly, this class and RoomClientHandler need better
names, but I couldn't think of anything.

1) Client allocates a free port to a UDP socket.
2) The client asks the server at a specified IP address to list the Rooms available.
3) The client asks to connect to a specific room.
4) The client is pinged by the server's RoomClientHandler created for this client and waits until it is notified that the game is starting.
5) From there, an implementation of the game or network-based application can be executed on the server to interact with this client.

This project didn't have an implementation of a game or network-based application. Partly because I was really lazy but also because
I didn't have the time, but also because I was mostly only motivated to do a waiting room.
 */
public class Client {
    public Client(){
        try{
            byte[] receiveBuffer = new byte[1024];
            byte[] sendBuffer = new byte[0];
            DatagramPacket dgPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            DatagramSocket clientSocket = new DatagramSocket();
            InetAddress receiverAddress = InetAddress.getByName("127.0.0.1");
            int roomServerPort = 38950;
            String msgFromClient = "";
            String msgToClient = "";
            int roomPort;
            int roomClientPort;

            //Connect to room
            roomPort = decideRoom(clientSocket, dgPacket, sendBuffer, receiveBuffer, receiverAddress, roomServerPort);
            if(roomPort == 0){ //server never responded
                System.out.println("There was no response from the server after 3 attempts to connect. Shutting down.");
            } else {
                roomClientPort = connectToRoom(clientSocket, dgPacket, sendBuffer, receiveBuffer, receiverAddress, roomPort);

                //Wait for room: pinging action.
                waitForGame(clientSocket, dgPacket, sendBuffer, receiveBuffer, receiverAddress, roomClientPort);
            }

            //game and all related connections finished
            clientSocket.close();
        }catch(IOException e){
            e.printStackTrace();
        }
    }

    public int decideRoom(DatagramSocket clientSocket, DatagramPacket dgPacket, byte[] sendBuffer, byte[] receiveBuffer, InetAddress receiverAddress, int roomServerPort) throws IOException {
        String msgFromClient = "";
        String msgToClient = "";
        int roomPort = 0;
        int timeoutLimit = 3;
        int timeouts = 0;

        while(roomPort == 0 && timeouts < timeoutLimit){  //keep pinging until server sends something or until the user halts the program.
            try{
                clientSocket.setSoTimeout(6000); //set a timeout for receiving messages. If we don't get anything in 6 seconds,
                ///assume that the server is dead.
                //let the user see all rooms
                msgToClient = "list";
                sendString(clientSocket, dgPacket, sendBuffer, msgToClient, receiverAddress, roomServerPort);
                System.out.println("Waiting to receive from server...");
                msgFromClient = receiveString(clientSocket, dgPacket, receiveBuffer);
                System.out.println("Server sent message!");
                System.out.println(msgFromClient);
                Scanner scanner = new Scanner(System.in);

                //let the user pick a room
                msgToClient = "connect";
                sendString(clientSocket, dgPacket, sendBuffer, msgToClient, receiverAddress, roomServerPort);
                System.out.println("Which room do you want to join?");
                msgToClient = scanner.nextLine();
                sendString(clientSocket, dgPacket, sendBuffer, msgToClient, receiverAddress, roomServerPort);
                msgFromClient = receiveString(clientSocket, dgPacket, receiveBuffer);
                System.out.println(msgFromClient);
                roomPort = Integer.parseInt(msgFromClient);
                scanner.close();
            }catch(SocketTimeoutException e){ //if we timeout from not reading anything just retry.
                timeouts++;
                continue;
            }
        }


        return roomPort;
    }

    public int connectToRoom(DatagramSocket clientSocket, DatagramPacket dgPacket, byte[] sendBuffer, byte[] receiveBuffer, InetAddress receiverAddress, int roomPort) throws IOException{
        String msgFromClient = "";
        String msgToClient = "";
        int portToConnectTo = 0;

        msgToClient = "connect";
        sendString(clientSocket, dgPacket, sendBuffer, msgToClient, receiverAddress, roomPort); //cannot connect twice: handled in Room.java loop.

        msgFromClient = receiveString(clientSocket, dgPacket, receiveBuffer);
        System.out.println(msgFromClient);
        portToConnectTo = Integer.parseInt(msgFromClient);

        return portToConnectTo;
    }


    public void waitForGame(DatagramSocket clientSocket, DatagramPacket dgPacket, byte[] sendBuffer, byte[] receiveBuffer, InetAddress receiverAddress, int roomClientPort) throws IOException{
        boolean connected = true;
        System.out.println("connected to " + roomClientPort);

        String msgFromClient = "";
        String msgToClient = "";
        int consecutiveTimeouts = 0;
        int maxTimeouts = 3;
        int currentTime = 6000;

        while(connected){
            //check if a message is eventually received
            try{
                if((msgFromClient=receiveString(clientSocket, dgPacket, receiveBuffer)) == null || msgFromClient.equals("-1")){
                    //the server isn't connected anymore. Disconnect.
                    connected = false;
                    continue;
                }
                if(msgFromClient.equals("done")){ //if the wait room is full... break loop and start game
                    connected = true;
                    break;
                }
            } catch(SocketTimeoutException e) {
                consecutiveTimeouts++;
                currentTime = 6000 + (consecutiveTimeouts * 1000); //incrementally make wait time longer...
                clientSocket.setSoTimeout(currentTime);
                if(consecutiveTimeouts >= maxTimeouts){
                    System.out.println("Too many timeouts. Breaking connection");
                    connected = false;
                    continue;
                }
                System.out.println("No response was received in " + (currentTime/1000) + " seconds. Try " + (3-consecutiveTimeouts) + " more times!");
                continue;
            }

            //if the message was received, then reset timeout counter and set timeout timer to the normal time of 5 seconds. Respond to the ping.
            currentTime = 6000;
            consecutiveTimeouts = 0;
            clientSocket.setSoTimeout(currentTime);

            System.out.println("Receiver received: " + msgFromClient);
            msgToClient = "gotPing";
            sendString(clientSocket, dgPacket, sendBuffer, msgToClient, receiverAddress, roomClientPort);
        }
        if(connected){
            //TODO: CONTINUE GAME
            System.out.println("Starting game");
        }else{
            msgToClient = "-1";
            sendString(clientSocket, dgPacket, sendBuffer, msgToClient, receiverAddress, roomClientPort);
        }

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

    public static void main(String[] args){
        Client client = new Client();
    }
}
