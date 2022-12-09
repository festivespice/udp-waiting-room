package org.project;


import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
The purpose of this project is to provide a UDP-based waiting room for an online game. This is accomplished starting from this file.
This project doesn't use packages outside of the current Java installation, or any other package from a package manager like Maven.

1) The RoomServer object initializes a list of Rooms based on user inputs. These rooms have a limited number of allowed "connections,"
or people who can join the rooms. Because UDP is not connection-oriented, it isn't actually a connection.
2) The RoomServer has its own UDP port, but it also associates its Rooms with their own UDP ports. The purpose of the RoomServer is
to first capture all requests from Client objects over the network, and to act appropriately. It can either reply with
a list of the Rooms in the RoomServer, or it can add the current Client that sent a request to a specific Room that they
chose.
3) Once all Room objects in the RoomServer have completed their tasks and have been terminated, the RoomServer should also terminate
and release any ports it is using.

It is important to note that, because each Room is running concurrently, this is a multithreaded application and its multithreading
aspects are handled by an ExecutorService thread pool.

A downside to the code written is that it assumes that potentially several ranges of UDP ports are unused before allocating them. This could
be fixed by finding a certain number of free ports using some Java functions...
 */
public class RoomServer {
    private static String[] games = {"finger war"}; //we only have one game

    private ArrayList<Room> roomsList = new ArrayList<Room>();
    private ExecutorService roomThreads;

    public RoomServer(int serverPort){
        String ip = "127.0.0.1";
        int roomsStartingPort = serverPort + 2;

        createRoomThreads(roomsStartingPort, ip);
        handleRoomThreads(serverPort);

        System.out.println("Shutting down server...");
        roomThreads.shutdownNow(); //make sure that all threads are closed, if some are still open for whatever reason.
    }

    //Create rooms, which have ranges of ports.
    //Must be synchronized because the roomsList value in this parent thread must be updated from a child Room thread before we interact with it
    public synchronized void createRoomThreads(int roomsStartingPort, String ip){
        System.out.println("Initializing Rooms...");
        Scanner scanner = new Scanner(System.in);
        try{
            int maximumNumberRooms = 3;
            int maximumNumberPlayers = 5;

            int numberOfRooms = 0;
            System.out.println("Hello! How many rooms do you want to create? Up to "+maximumNumberRooms+".");
            numberOfRooms = validInput(1, 3, "rooms", scanner);
            roomThreads = Executors.newFixedThreadPool(numberOfRooms);

            int numberOfPlayers = 0;
            int currentRoom = 0;
            String desiredGame = "";
            for(int i = 1; i <= numberOfRooms; i++) {
                System.out.println("What game do you want to play in room " + i + "?");
                desiredGame = validStringInput(games, "game", scanner);

                System.out.println("How many players do you want in room " + i + "? Up to " + maximumNumberPlayers + " players per room.");
                numberOfPlayers = validInput(1, 5, "players", scanner);

                System.out.println("room " + i + " has " + numberOfPlayers + " players for game '" + desiredGame + "'.");

                try {
                    int currentRoomPort = (roomsStartingPort + (i * maximumNumberPlayers));
                    DatagramSocket roomSocket = new DatagramSocket(currentRoomPort); //each port has enough additional ports for players
                    Room newRoom = new Room(this, roomSocket, ip, desiredGame, "Room " + i, numberOfPlayers, currentRoomPort);
                    roomThreads.submit(newRoom);
                    wait(); //make sure that the room is added to this class's arraylist from the child thread before continuing. Added from the thread's initializeRoom() function from run().
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            roomThreads.shutdown(); //ensure that each thread terminates when it's done.
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        //close the scanner after adding all of the rooms
        scanner.close();
    }

    //Handle requests to the roomserver, offering different rooms to connect to and returning the port of the desired room.
    //When all rooms are closed, this function will also shut down the server.
    public void handleRoomThreads(int serverPort){
        try{
            System.out.println("Starting server...");
            DatagramSocket roomServerSocket = new DatagramSocket(serverPort);
            boolean receivingRequests = true;
            byte[] receiveBuffer = new byte[1024];
            byte[] sendBuffer = new byte[0];
            DatagramPacket dgPacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            String msgFromClient = "";
            String msgToClient = "";
            int timeBeforeTimeout = 20000;

            //blocking loop: one client at a time. Waits at receiveString() because .receive() is a blocking function
            int latestClientPort;
            InetAddress latestClientAddress;


            while(receivingRequests && roomsList.size() >= 1){
                if(roomsList.size() <= 2){ //so that we don't waste too many resources, only check if the rooms are closed if there are less than 3 rooms.
                    roomServerSocket.setSoTimeout(timeBeforeTimeout);
                }
                try{
                    msgFromClient = receiveString(roomServerSocket, dgPacket, receiveBuffer); //wait 20 seconds
                }catch(SocketTimeoutException e){
                    //We use socket timeouts to allow our server to check if rooms are still open
                    continue;
                }
                if(msgFromClient.equals("list")){ //if client wants to see all rooms
                    System.out.println("Received request to list");
                    latestClientAddress = dgPacket.getAddress();
                    latestClientPort = dgPacket.getPort();

                    msgToClient = returnRoomsString();
                    sendString(roomServerSocket, dgPacket, sendBuffer, msgToClient, latestClientAddress, latestClientPort);
                }
                if(msgFromClient.equals("connect")){ //if client wants to connect to a room

                    System.out.println("Received request to connect");
                    latestClientAddress = dgPacket.getAddress();
                    latestClientPort = dgPacket.getPort();

                    //add a try catch for socket timeout in case someone a client lists and then tries to connect but doesn't specify a room
                    try {
                        //await client response. Client response must be a room listed.
                        while(!contains(roomsList, (msgFromClient = receiveString(roomServerSocket, dgPacket, receiveBuffer)))){
                            msgToClient = "That isn't a room listed. For example, the first room listed is " + roomsList.get(1).serverName + ".";
                            sendString(roomServerSocket, dgPacket, sendBuffer, msgToClient, latestClientAddress, latestClientPort);
                            //loop back to the above while condition to see if the response is correct.
                        }
                    }catch(SocketTimeoutException e){
                        System.out.println("Client didn't respond to connect");
                        continue;
                    }

                    //now send a string of the port of the room requested.
                    Room desiredRoom = findRoom(roomsList, msgFromClient);
                    msgToClient = String.valueOf(desiredRoom.serverPort);
                    sendString(roomServerSocket, dgPacket, sendBuffer, msgToClient, latestClientAddress, latestClientPort);
                }
            }

            //after all rooms are closed, shutdown.
            roomServerSocket.close();
        }catch(IOException e){
            e.printStackTrace();
        }
    }

    //inputs: port of server, maximum number of players per room, maximum number of rooms
    public static void main(String[] args){
        if(args.length == 0){
            RoomServer roomServer = new RoomServer(38950);
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

    public int validInput(int atLeast, int atMost, String thing, Scanner scanner){
        int desiredNumber = 0;
        while((desiredNumber = Integer.parseInt(scanner.nextLine())) > atMost || desiredNumber < atLeast){
            if(desiredNumber < atLeast){
                System.out.println("At least "+atLeast+" "+thing+" must be allowed.");
            }else{
                System.out.println("Your input is too high. It has to be at most "+atMost+".");
            }
            System.out.println("Try again:");
        }
        return desiredNumber;
    }

    public String validStringInput(String[] listThings, String thing, Scanner scanner){
        String desiredThing = "";
        while(!contains(listThings, desiredThing = scanner.nextLine())){ //the input isn't in the arraylist
            System.out.println(desiredThing + " isn't in the list of "+thing+"s.");
            for(String availableThing: listThings){
                System.out.println(availableThing + " is a possible " + thing);
            }
            System.out.println("Try again:");
        }
        return desiredThing;
    }

    public String returnRoomsString(){
        StringBuilder sb = new StringBuilder();
        int i = 1;

        sb.append("Available rooms:\n");
        for(Room room:roomsList){
            sb.append( + i + ", name: "+room.serverName+", game: "+room.serverGame+".\n");
            i++;
        }
        return sb.toString();
    }

    public boolean contains(String[] list, String string){
        for(int i =0; i < list.length; i++){
            if(string.equals(list[i])){
                return true;
            }
        }
        return false;
    }

    public boolean contains(ArrayList<Room> list, String string){
        for(int i =0; i <= list.size(); i++){
            if(string.equals(list.get(i).serverName)){ //all the names of the roomsList
                return true;
            }
        }
        return false;
    }

    public Room findRoom(ArrayList<Room> list, String roomName){
        for(int i =0; i <= list.size(); i++){
            if(list.get(i).serverName.equals(roomName)){
                return list.get(i);
            }
        }
        return null;
    }

    //A child room thread will call this function so that they're removed from the list.
    public void removeRoom(Room room){
        System.out.println("Closing room " + room.serverName);
        room.serverSocket.close();
        roomsList.remove(room);
    }

    public synchronized void addRoom(Room room){
        roomsList.add(room);
        notify(); //notifies the createRoomsThread function at the end of the loop
    }
}
