package com.router;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.*;

public class util {

    private static DatagramSocket socket;

    public static InetAddress getValidAddress(String address) {
        try {
            return Inet4Address.getByName(address);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static boolean checkPortAvailability(int port) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(port);
            socket.close();
            return true;
        } catch (Exception e) {
            if(socket != null) {
                socket.close();
            }
            return false;
        }
    }


    public static boolean tryCreateSocket(InetAddress addr, int port) {
        if(!checkPortAvailability(port)) {
            return false;
        }
        try {
            socket = new DatagramSocket(port);
            return true;
        } catch (IOException e) {
            printErr("Failed to create socket");
            return false;
        }

    }

  //send packet to ip and port
    public static void sendPacket(InetAddress addr, int port, byte[] sendData) throws IOException {
        try {
            //send server the packet
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, addr, port);
            socket.send(sendPacket);
        } catch (IOException e) {
            throw e;
        }
    }


    //receive a packet from given port
    public static byte[] receivePacket() throws IOException {
        byte[] receiveData = new byte[1024];
        byte[] actualData;

        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        socket.receive(receivePacket);

        actualData = new byte[receivePacket.getLength()];
        System.arraycopy(
                receivePacket.getData(),
                receivePacket.getOffset(),
                actualData,
                0,
                receivePacket.getLength()
        );

        return actualData;
    }


    public static PrintWriter createFileWriter(String name) throws IOException {
        return new PrintWriter(
                new BufferedWriter(
                        new FileWriter(name, true)
                )
        );
    }

    public static void printOut(String msg) {
        System.out.println(msg);
    }

    public static void printErr(String err) {
        System.err.println(err);
    }
}
