package com.router;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class router {

    private static final int NBR_ROUTER = 5;
    private static final int maxEdges = NBR_ROUTER * (NBR_ROUTER-1) / 2;
    private static circuit_DB db;
    private static boolean activeLinks[];
    private static link_cost[] topology[];
    private static rib_entry rib[];
    private static String logname;
    private static PrintWriter logger;
    private static int linkCount[];

    public static void main(String[] args) {

        if(args.length < 4) {
            util.printErr("Invalid number of arguments");
            return;
        }

        String nseHost;
        nseHost = args[1];

        int routerId, nsePort, routerPort;

        try {
            routerId = Integer.parseInt(args[0]);
            nsePort = Integer.parseInt(args[2]);
            routerPort = Integer.parseInt(args[3]);
        } catch(NumberFormatException e) {
            util.printErr("invalid port numbers");
            return;
        }

        InetAddress ipAddress;
        if((ipAddress = util.getValidAddress(nseHost)) == null) {
            util.printErr("Invalid nse host");
            return;
        }

        if(!util.tryCreateSocket(ipAddress, routerPort)) {
            util.printErr("Router Port unavailable");
            return;
        }

        logname = "router" + routerId + ".log";

        initStructures(routerId);

        //send the init packet
        pkt_INIT initPkt = new pkt_INIT();
        initPkt.router_id = routerId;

        try {
            util.sendPacket(ipAddress, nsePort, initPkt.getByteArray());
            logINIT(routerId);
        } catch (IOException e) {
            util.printErr("Failed to send init packet");
            return;
        }

        //receive the circuit db
        try {
            byte[] rec = util.receivePacket();
            db = circuit_DB.parseDB(rec);

            logCBD(routerId, db.nbr_link);

            for(int i = 0; i < NBR_ROUTER; i++) {
                topology[i] = new link_cost[maxEdges];
            }

            for(int i = 0; i < db.nbr_link; i++) {
                topology[routerId-1][db.linkcost[i].link - 1] = db.linkcost[i]; //index using link id (offset by 1)
            }

            dijkstra(routerId, rib);
            linkCount[routerId-1] = db.nbr_link;

            logTopology(routerId);
            logRIB(routerId);

        } catch (IOException e) {
            util.printErr("Failed to receive packet " + e.getMessage());
            return;
        }

        //send the hello packets
        for (int i = 0; i < db.nbr_link; i++) {

            pkt_HELLO hello = new pkt_HELLO(routerId, db.linkcost[i].link);
            logHELLO(true, routerId, hello.router_id, hello.link_id);
            try {
                util.sendPacket(ipAddress, nsePort, hello.getByteArray());
            } catch (IOException e) {
                util.printErr("failed to send hello " + e.getMessage());
                return;
            }
        }

        while(true) {
            try {
                byte[] rec = util.receivePacket();

                //hello received
                if(rec.length == Integer.BYTES * 2) {
                    pkt_HELLO hello = pkt_HELLO.parseHello(rec);

                    logHELLO(false, routerId, hello.router_id, hello.link_id);

                    activeLinks[hello.link_id - 1] = true;

                    for(int i = 0; i < router.topology.length; i++) {
                        if (router.topology[i] == null) {
                            continue;
                        }

                        for (int j = 0; j < router.topology[i].length; j++) {
                            if(router.topology[i][j] == null) {
                                continue;
                            }

                            //send the ls pdu
                            pkt_LSPDU sendLS = new pkt_LSPDU(
                                    routerId,
                                    i + 1,
                                    router.topology[i][j].link,
                                    router.topology[i][j].cost,
                                    hello.link_id
                            );

                            logLSU(true, sendLS, routerId);
                            try {
                                util.sendPacket(
                                        ipAddress,
                                        nsePort,
                                        sendLS.getByteArray()
                                );
                            } catch (IOException io) {
                                util.printErr("Failed to send LSPDU");
                                return;
                            }
                        }
                    }

                } else { //ls received
                    pkt_LSPDU ls = pkt_LSPDU.parseLSPDU(rec);

                    logLSU(false, ls, routerId);
                    //don't send ls pdu again if we've already sent it
                    if( topology[ls.router_id-1][ls.link_id-1] != null ) {
                        continue;
                    }

                    //update topology
                    topology[ls.router_id-1][ls.link_id-1] = new link_cost(ls.link_id, ls.cost);
                    linkCount[ls.router_id-1]++;

                    for(int i = 0; i < db.nbr_link; i++) {
                        //don't resend to sender, don't send to neighbours who didn't send hello
                        if(db.linkcost[i].link == ls.via || !activeLinks[db.linkcost[i].link - 1]) {
                            continue;
                        }

                        pkt_LSPDU sendLS = new pkt_LSPDU(
                                routerId,
                                ls.router_id,
                                ls.link_id,
                                ls.cost,
                                db.linkcost[i].link
                        );

                        logLSU(true, sendLS, routerId);

                        util.sendPacket(
                                ipAddress,
                                nsePort,
                                sendLS.getByteArray()
                        );
                    }

                    //recompute using dijkstra
                    dijkstra(routerId, rib);
                    logTopology(routerId);
                    logRIB(routerId);
                }

            } catch (IOException e) {
                util.printErr("Failed to receive a packet");
                return;
            }
        }
    }

    private static void logRIB(int routerId) {
        try {
            logger = util.createFileWriter(logname);
        } catch (IOException e) {
            util.printErr("Failed to create logger");
            return;
        }

        logger.println("# RIB");
        for (int i = 0; i < NBR_ROUTER; i++) {
            String cost = rib[i].totalCost < Integer.MAX_VALUE ? Integer.toString(rib[i].totalCost) : "INF";
            String next = rib[i].nextHop > 0 ? Integer.toString(rib[i].nextHop) : "???";

            if(rib[i].nextHop == routerId) {
                next = "Local";
            }

            String prefix = next == "Local" ? "" : "R";

            logger.println("R" + routerId +
                    " -> " + "R" + Integer.toString(i+1)
                    + " -> " + prefix + next + ", " + cost);
        }

        logger.close();
    }

    private static void logTopology(int routerId) {
        try {
            logger = util.createFileWriter(logname);
        } catch (IOException e) {
            util.printErr("Failed to create logger");
            return;
        }

        logger.println("# Topology Database");
        for (int i = 0; i < NBR_ROUTER; i++) {
            if(topology[i] == null) {
                continue;
            }

            logger.println("R" + routerId +
                    " -> " + "R" + Integer.toString(i+1) + " nbr link " + linkCount[i]);

            for(int j = 0; j < maxEdges; j++) {
                if(topology[i][j] == null) {
                    continue;
                }

                logger.println("R" + routerId +
                        " -> " + "R" + Integer.toString(i + 1) +
                        " link " + topology[i][j].link + " cost " + topology[i][j].cost);
            }
        }

        logger.close();
    }

    private static void logHELLO(boolean isSend, int router, int router_id, int link) {
        try {
            logger = util.createFileWriter(logname);
        } catch (IOException e) {
            util.printErr("Failed to create logger");
            return;
        }

        String mode = isSend ? " sends" : " receives";

        logger.println("R" + router + mode + " a HELLO: router_id " + router_id + " link_id" + link);
        logger.close();
    }

    private static void logINIT(int router) {
        try {
            logger = util.createFileWriter(logname);
        } catch (IOException e) {
            util.printErr("Failed to create logger");
            return;
        }

        logger.println("R" + router + " sends an INIT: router_id " + router);
        logger.close();
    }

    private static void logCBD(int router, int nbrlink) {
        try {
            logger = util.createFileWriter(logname);
        } catch (IOException e) {
            util.printErr("Failed to create logger");
            return;
        }

        logger.println("R" + router + " receives a CIRCUIT_DB: nbr_link  " + nbrlink);
        logger.close();
    }

    private static void logLSU(boolean isSend, pkt_LSPDU ls, int routerId) {

        try {
            logger = util.createFileWriter(logname);
        } catch (IOException e) {
            util.printErr("Failed to create logger");
            return;
        }

        String mode = isSend ? " sends " : " receives ";
        String template = "an LS PDU: ";

        logger.println("R" + routerId + mode + template
                + "sender " + ls.sender + ", router_id " +
                ls.router_id + ", link_id " + ls.link_id + ", cost " + ls.cost + ", via " + ls.via
        );

        logger.close();
    }

    private static void initStructures(int routerId) {
        activeLinks = new boolean[maxEdges];
        linkCount = new int[NBR_ROUTER];

        for(int i = 0; i < maxEdges; i++) {
            activeLinks[i] = false;
        }

        topology = new link_cost[][] {null, null, null, null, null};
        rib = new rib_entry[5];

        for(int i = 0; i < NBR_ROUTER; i++) {
            rib[i] = new rib_entry(-1, Integer.MAX_VALUE);
        }

        rib[routerId-1] = new rib_entry(routerId, 0); //init for self
    }

    private static void dijkstra(int start, rib_entry[] rib) {
        for(int i = 0; i < NBR_ROUTER; i++) {
            int node = i + 1;

            if(start == node) {
                continue;
            }

            int edgeCost = edgeCost(start, node);

            if(edgeCost > 0) {
                rib[node-1].totalCost = edgeCost;
                rib[node-1].nextHop = node;
            } else {
                rib[node-1].totalCost = Integer.MAX_VALUE;
                rib[node-1].nextHop = -1;
            }
        }

        boolean visited[] = new boolean[NBR_ROUTER];
        for(int i = 0; i < NBR_ROUTER; i++) {
            visited[i] = false;
        }
        visited[start-1] = true;
        int visitedCount = 1;

        while(visitedCount < NBR_ROUTER) {
            int w = getMinRouter(visited);

            if(w < 0) {
                return;
            }

            visited[w] = true;
            visitedCount++;

            for(int i = 0; i < NBR_ROUTER; i++) {
                if(visited[i]) {
                    continue;
                }

                int edgeCost = edgeCost(w+1, i+1);

                if(edgeCost < 0) {
                    continue;
                }

                int newCost = edgeCost + rib[w].totalCost;
                if(rib[i].totalCost < newCost) {
                    continue;
                }

                rib[i].totalCost = newCost;
                rib[i].nextHop = rib[w].nextHop;
            }
        }
    }

    private static int getMinRouter(boolean exclude[]) {
        int min = Integer.MAX_VALUE;
        int minRouter = -1;
        for(int i = 0; i < NBR_ROUTER; i++) {
            if(exclude[i]) {
                continue;
            }

            if(rib[i].totalCost < min) {
                min = rib[i].totalCost;
                minRouter = i;
            }
        }

        return minRouter;
    }

    private static int edgeCost(int router1, int router2) {
        for (int i = 0; i < maxEdges; i++) {
            if(topology[router1-1][i] == null) {
                continue;
            }

            for (int j = 0; j < maxEdges; j++) {
                if(topology[router2-1][j] == null) {
                    continue;
                }

                if(topology[router1-1][i].link == topology[router2-1][j].link) {
                    return topology[router1-1][i].cost;
                }
            }
        }

        return -1;
    }

}

class rib_entry {
    public int nextHop;
    public int totalCost; //total cost of getting to dest

    public rib_entry(int nextHop, int totalCost) {
        this.nextHop = nextHop;
        this.totalCost = totalCost;
    }
}

class pkt_HELLO {
    public int router_id;
    public int link_id;

    public pkt_HELLO(int routerid, int linkid) {
        router_id = routerid;
        link_id = linkid;
    }

    public static pkt_HELLO parseHello(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int router_id = buffer.getInt();
        int link_id = buffer.getInt();

        return new pkt_HELLO(router_id, link_id);
    }

    public byte[] getByteArray() {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 2);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(router_id);
        buffer.putInt(link_id);

        return buffer.array();
    }
}

class pkt_LSPDU {
    public int sender;
    public int router_id;
    public int link_id;
    public int cost;
    public int via;

    public pkt_LSPDU(int sender, int router_id, int link_id, int cost, int via) {
        this.sender = sender;
        this.router_id = router_id;
        this.link_id = link_id;
        this.cost = cost;
        this.via = via;
    }

    public static pkt_LSPDU parseLSPDU(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int sender = buffer.getInt();
        int router_id = buffer.getInt();
        int link_id = buffer.getInt();
        int cost = buffer.getInt();
        int via = buffer.getInt();

        return new pkt_LSPDU(sender, router_id, link_id, cost, via);
    }

    public byte[] getByteArray() {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 5);

        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(sender);
        buffer.putInt(router_id);
        buffer.putInt(link_id);
        buffer.putInt(cost);
        buffer.putInt(via);

        return buffer.array();
    }
}

class pkt_INIT {
    public int router_id;

    public byte[] getByteArray() {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);

        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(router_id);

        return buffer.array();
    }
}

class link_cost {
    public int link;
    public int cost;

    public link_cost(int link, int cost) {
        this.link = link;
        this.cost = cost;
    }

    public byte[] getByteArray() {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 2);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(link);
        buffer.putInt(cost);

        return buffer.array();
    }
}

class circuit_DB {
    public int nbr_link;
    link_cost linkcost[];

    circuit_DB(int nbrLink, link_cost linkCost[]) {
        nbr_link = nbrLink;
        linkcost = linkCost;
    }

    public static circuit_DB parseDB(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int nbrlink = buffer.getInt();
        link_cost cost[] = new link_cost[nbrlink];
        for(int i = 0; i < nbrlink; i++) {
            cost[i] = new link_cost(buffer.getInt(), buffer.getInt());
        }

        return new circuit_DB(nbrlink, cost);
    }
}

