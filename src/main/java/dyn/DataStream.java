package dyn;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Receives IENA-format UDP packets and exposes named variables to listeners.
 * The variable names and count are loaded from a XidML metadata file, removing
 * the previous hardcoded a-z limitation.
 *
 * Supports both unicast (direct UDP to a port) and multicast (join a network
 * group, like the Python sender/receiver scripts).
 *
 * IENA packet layout (big-endian):
 *   Header  (16 B): key(2) + size(2) + time_high(4) + time_low(2) + status(2) + seq(2) + n2(2)
 *   Payload (N×4 B): N × float32
 *   Trailer (2 B):   0xDEAD
 */
public class DataStream {

    public enum Mode { UNICAST, MULTICAST, BROADCAST }

    /** Metadata for a single stream parameter, parsed from XidML. */
    public record ParameterInfo(String name, int index, String unit,
                                double rangeMin, double rangeMax) {}

    /** Discovery result for one observed IENA stream. */
    public record StreamInfo(int key, String sourceIp, int packetCount, double packetsPerSecond) {
        public String keyHex() {
            return String.format("0x%04X", key);
        }
    }

    private static final int IENA_TRAILER = 0xDEAD;
    private static final int HEADER_SIZE = 16;
    private static final int TRAILER_SIZE = 2;

    private final List<ParameterInfo> parameterInfos;
    private final int numParams;
    private final int payloadSize;
    private final int expectedPktSize;
    private final String[] paramNames;
    private final NetworkInterface networkInterface; // null = all / OS default

    private final Map<String, Double> values = new ConcurrentHashMap<>();
    private final List<Consumer<Map<String, Double>>> listeners = new ArrayList<>();
    private final Mode mode;
    private final int port;
    private final String multicastGroup;
    private final int keyFilter; // -1 = accept all
    private volatile boolean running;
    private Thread listenerThread;

    // ------------------------------------------------------------------
    // XidML loading
    // ------------------------------------------------------------------

    /**
     * Parse a XidML metadata file and return parameter definitions sorted by index.
     */
    public static List<ParameterInfo> loadXidML(String filePath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new File(filePath));
        doc.getDocumentElement().normalize();

        NodeList paramNodes = doc.getElementsByTagName("Parameter");
        List<ParameterInfo> params = new ArrayList<>();

        for (int i = 0; i < paramNodes.getLength(); i++) {
            Element el = (Element) paramNodes.item(i);
            String name = el.getAttribute("name");
            int index = Integer.parseInt(el.getAttribute("index"));

            String unit = getElementText(el, "Unit", "");
            double rangeMin = Double.parseDouble(getElementText(el, "RangeMinimum", "0.0"));
            double rangeMax = Double.parseDouble(getElementText(el, "RangeMaximum", "100.0"));

            params.add(new ParameterInfo(name, index, unit, rangeMin, rangeMax));
        }

        params.sort(Comparator.comparingInt(ParameterInfo::index));
        return params;
    }

    /** Helper: get the text content of the first child element with the given tag, or a default. */
    private static String getElementText(Element parent, String tag, String defaultValue) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() > 0) {
            String text = nl.item(0).getTextContent().trim();
            if (!text.isEmpty()) return text;
        }
        return defaultValue;
    }

    /**
     * Build the default a-z parameter list (legacy fallback when no XidML is provided).
     */
    static List<ParameterInfo> defaultParameters() {
        List<ParameterInfo> params = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            String name = String.valueOf((char) ('a' + i));
            params.add(new ParameterInfo(name, i, "", 0.0, i + 1));
        }
        return params;
    }

    // ------------------------------------------------------------------
    // Constructors
    // ------------------------------------------------------------------

    /** Unicast on default port 5000, default a-z parameters. */
    public DataStream() {
        this(Mode.UNICAST, 5000, null, -1, defaultParameters());
    }

    /** Unicast on the given port, default a-z parameters. */
    public DataStream(int port) {
        this(Mode.UNICAST, port, null, -1, defaultParameters());
    }

    /** 3-arg constructor — backward compatible, accepts all keys, default a-z. */
    public DataStream(Mode mode, int port, String multicastGroup) {
        this(mode, port, multicastGroup, -1, defaultParameters());
    }

    /** 4-arg constructor — backward compatible, default a-z parameters. */
    public DataStream(Mode mode, int port, String multicastGroup, int keyFilter) {
        this(mode, port, multicastGroup, keyFilter, defaultParameters());
    }

    /**
     * 5-arg constructor — backward compatible, no interface selection.
     */
    public DataStream(Mode mode, int port, String multicastGroup, int keyFilter,
                      List<ParameterInfo> parameterInfos) {
        this(mode, port, multicastGroup, keyFilter, parameterInfos, null);
    }

    /**
     * Full constructor — choose mode, port, multicast group, key filter, parameters,
     * and optionally a specific network interface.
     *
     * @param mode             UNICAST, MULTICAST, or BROADCAST
     * @param port             UDP port to listen on
     * @param multicastGroup   multicast group address (e.g. "239.1.1.1"), ignored for unicast/broadcast
     * @param keyFilter        IENA key to accept (-1 = accept all)
     * @param parameterInfos   parameter definitions (from XidML or default)
     * @param networkInterface specific interface to use (null = all / OS default)
     */
    public DataStream(Mode mode, int port, String multicastGroup, int keyFilter,
                      List<ParameterInfo> parameterInfos, NetworkInterface networkInterface) {
        this.mode = mode;
        this.port = port;
        this.multicastGroup = multicastGroup;
        this.keyFilter = keyFilter;
        this.networkInterface = networkInterface;
        this.parameterInfos = List.copyOf(parameterInfos);
        this.numParams = parameterInfos.size();
        this.payloadSize = numParams * 4;
        this.expectedPktSize = HEADER_SIZE + payloadSize + TRAILER_SIZE;
        this.paramNames = new String[numParams];
        for (int i = 0; i < numParams; i++) {
            paramNames[i] = parameterInfos.get(i).name();
            values.put(paramNames[i], 0.0);
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public Mode getMode() { return mode; }
    public int getPort() { return port; }
    public String getMulticastGroup() { return multicastGroup; }
    public int getKeyFilter() { return keyFilter; }

    /** Ordered list of variable names from the XidML definition. */
    public List<String> getVariableNames() {
        return List.of(paramNames);
    }

    /** Full parameter metadata from the XidML definition. */
    public List<ParameterInfo> getParameterInfos() {
        return parameterInfos;
    }

    /** Human-readable connection info string. */
    public String getConnectionInfo() {
        StringBuilder sb = new StringBuilder();
        if (mode == Mode.MULTICAST) {
            sb.append(multicastGroup).append(":").append(port);
        } else if (mode == Mode.BROADCAST) {
            sb.append("broadcast port ").append(port);
        } else {
            sb.append("port ").append(port);
        }
        if (keyFilter >= 0) {
            sb.append(" [key ").append(String.format("0x%04X", keyFilter)).append("]");
        }
        if (networkInterface != null) {
            sb.append(" [").append(networkInterface.getDisplayName()).append("]");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    public void start() {
        running = true;
        listenerThread = new Thread(this::listen, "IENA-Receiver");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void listen() {
        DatagramSocket socket = null;
        MulticastSocket multicastSocket = null;
        List<InetSocketAddress> joinedGroups = new ArrayList<>();
        List<NetworkInterface> joinedIfaces = new ArrayList<>();

        try {
            if (mode == Mode.MULTICAST) {
                multicastSocket = createMulticastSocket(port);
                InetAddress group = InetAddress.getByName(multicastGroup);
                InetSocketAddress groupAddr = new InetSocketAddress(group, port);
                if (networkInterface != null) {
                    multicastSocket.joinGroup(groupAddr, networkInterface);
                    joinedGroups.add(groupAddr);
                    joinedIfaces.add(networkInterface);
                } else {
                    joinMulticastOnAllInterfaces(multicastSocket, groupAddr, joinedGroups, joinedIfaces);
                }
                socket = multicastSocket;
                String ifaceInfo = networkInterface != null
                        ? " on " + networkInterface.getDisplayName() : "";
                System.out.println("Joined multicast group " + multicastGroup
                        + " on UDP port " + port + ifaceInfo);
            } else if (mode == Mode.BROADCAST) {
                DatagramSocket ds = new DatagramSocket(null);
                ds.setReuseAddress(true);
                ds.setBroadcast(true);
                InetAddress bindAddr;
                if (networkInterface != null) {
                    InetAddress ifaceAddr = getIPv4Address(networkInterface);
                    bindAddr = ifaceAddr != null ? ifaceAddr
                            : InetAddress.getByName("0.0.0.0");
                } else {
                    bindAddr = InetAddress.getByName("0.0.0.0");
                }
                ds.bind(new InetSocketAddress(bindAddr, port));
                socket = ds;
                String ifaceInfo = networkInterface != null
                        ? " on " + networkInterface.getDisplayName() : "";
                System.out.println("Listening for broadcast IENA packets on UDP port "
                        + port + ifaceInfo + " ...");
            } else {
                socket = new DatagramSocket(port);
                System.out.println("Listening for IENA packets on UDP port " + port + " ...");
            }

            socket.setSoTimeout(1000);
            byte[] buf = new byte[4096];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);

            while (running) {
                try {
                    packet.setLength(buf.length); // reset before each receive
                    socket.receive(packet);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }

                byte[] data = packet.getData();
                int len = packet.getLength();

                // Key filtering: skip packets that don't match the desired key
                if (keyFilter >= 0) {
                    int pktKey = parseIenaKey(data, len);
                    if (pktKey != keyFilter) continue;
                }

                float[] parsed = parseIenaPacket(data, len);
                if (parsed == null) continue;

                for (int i = 0; i < numParams; i++) {
                    values.put(paramNames[i], (double) parsed[i]);
                }
                notifyListeners();
            }
        } catch (Exception e) {
            if (running) {
                System.err.println("IENA receiver error: " + e.getMessage());
                e.printStackTrace();
            }
        } finally {
            leaveAllGroups(multicastSocket, joinedGroups, joinedIfaces);
            if (socket != null) socket.close();
        }
    }

    // ------------------------------------------------------------------
    // Multicast helpers
    // ------------------------------------------------------------------

    /**
     * Create a MulticastSocket with SO_REUSEADDR set BEFORE binding
     * (matches Python's socket setup order).
     */
    private static MulticastSocket createMulticastSocket(int port) throws IOException {
        MulticastSocket socket = new MulticastSocket(null); // create without binding
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));           // now bind
        return socket;
    }

    /**
     * Return all network interfaces suitable for multicast (up, supports multicast, non-loopback).
     */
    private static List<NetworkInterface> findMulticastInterfaces() throws SocketException {
        List<NetworkInterface> result = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (ni.isUp() && ni.supportsMulticast() && !ni.isLoopback()) {
                result.add(ni);
            }
        }
        return result;
    }

    /**
     * Return all usable network interfaces (up, non-loopback).
     * Includes both multicast and broadcast interfaces so callers
     * can use the list for either mode.
     */
    public static List<NetworkInterface> findUsableInterfaces() throws SocketException {
        List<NetworkInterface> result = new ArrayList<>();
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (ni.isUp() && !ni.isLoopback()) {
                result.add(ni);
            }
        }
        return result;
    }

    /**
     * Get the first IPv4 address from a network interface, or null if none.
     */
    static InetAddress getIPv4Address(NetworkInterface ni) {
        Enumeration<InetAddress> addrs = ni.getInetAddresses();
        while (addrs.hasMoreElements()) {
            InetAddress addr = addrs.nextElement();
            if (addr instanceof Inet4Address) return addr;
        }
        return null;
    }

    /**
     * Join a multicast group on ALL suitable network interfaces.
     */
    private static void joinMulticastOnAllInterfaces(
            MulticastSocket socket, InetSocketAddress groupAddr,
            List<InetSocketAddress> joinedGroups, List<NetworkInterface> joinedIfaces)
            throws IOException {
        List<NetworkInterface> interfaces = findMulticastInterfaces();
        if (interfaces.isEmpty()) {
            // Fallback: let the OS choose
            socket.joinGroup(groupAddr, null);
            joinedGroups.add(groupAddr);
            joinedIfaces.add(null);
        } else {
            for (NetworkInterface ni : interfaces) {
                try {
                    socket.joinGroup(groupAddr, ni);
                    joinedGroups.add(groupAddr);
                    joinedIfaces.add(ni);
                } catch (IOException e) {
                    System.err.println("Could not join " + groupAddr + " on " + ni.getDisplayName()
                            + ": " + e.getMessage());
                }
            }
        }
    }

    /** Leave all previously joined multicast groups. */
    private static void leaveAllGroups(MulticastSocket socket,
                                       List<InetSocketAddress> groups,
                                       List<NetworkInterface> ifaces) {
        if (socket == null) return;
        for (int i = 0; i < groups.size(); i++) {
            try {
                socket.leaveGroup(groups.get(i), ifaces.get(i));
            } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------
    // Packet parsing
    // ------------------------------------------------------------------

    /**
     * Parse an IENA packet. Returns the float values, or null if malformed.
     */
    private float[] parseIenaPacket(byte[] data, int length) {
        if (length < expectedPktSize) return null;

        ByteBuffer bb = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN);

        // skip header (16 bytes)
        bb.position(HEADER_SIZE);

        // read N floats
        float[] vals = new float[numParams];
        for (int i = 0; i < numParams; i++) {
            vals[i] = bb.getFloat();
        }

        // check trailer
        int trailer = bb.getShort() & 0xFFFF;
        if (trailer != IENA_TRAILER) return null;

        return vals;
    }

    /**
     * Extract the IENA key (first 2 bytes) from raw packet data.
     * Returns -1 if packet is too short.
     */
    private static int parseIenaKey(byte[] data, int length) {
        if (length < 2) return -1;
        return ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
    }

    // ------------------------------------------------------------------
    // Stream discovery
    // ------------------------------------------------------------------

    /** Discover streams on all interfaces (backward compatible). */
    public static List<StreamInfo> discoverStreams(List<String> groups, int port, double durationSeconds)
            throws IOException {
        return discoverStreams(groups, port, durationSeconds, null);
    }

    /**
     * Discover active IENA streams on the given multicast groups.
     * Listens for {@code durationSeconds} and returns info about each unique (key, sourceIp) pair.
     *
     * @param networkInterface specific interface to join on (null = all interfaces)
     */
    public static List<StreamInfo> discoverStreams(List<String> groups, int port,
                                                   double durationSeconds,
                                                   NetworkInterface networkInterface)
            throws IOException {
        MulticastSocket socket = createMulticastSocket(port);
        socket.setSoTimeout(500); // match Python's 0.5s timeout

        // Join all requested groups
        List<InetSocketAddress> joinedGroups = new ArrayList<>();
        List<NetworkInterface> joinedIfaces = new ArrayList<>();
        for (String group : groups) {
            InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(group), port);
            if (networkInterface != null) {
                socket.joinGroup(addr, networkInterface);
                joinedGroups.add(addr);
                joinedIfaces.add(networkInterface);
            } else {
                joinMulticastOnAllInterfaces(socket, addr, joinedGroups, joinedIfaces);
            }
        }

        // key: "ienaKey|sourceIp"  value: packet count
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> sourceIps = new LinkedHashMap<>();
        Map<String, Integer> keys = new LinkedHashMap<>();

        byte[] buf = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        long startMs = System.currentTimeMillis();
        long durationMs = (long) (durationSeconds * 1000);

        while (System.currentTimeMillis() - startMs < durationMs) {
            try {
                packet.setLength(buf.length); // reset before each receive
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            }
            int key = parseIenaKey(packet.getData(), packet.getLength());
            if (key < 0) continue;

            String srcIp = packet.getAddress().getHostAddress();
            String mapKey = key + "|" + srcIp;
            counts.computeIfAbsent(mapKey, k -> new int[1])[0]++;
            sourceIps.putIfAbsent(mapKey, srcIp);
            keys.putIfAbsent(mapKey, key);
        }

        // Leave all groups and close
        leaveAllGroups(socket, joinedGroups, joinedIfaces);
        socket.close();

        // Build results
        List<StreamInfo> results = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            String mk = entry.getKey();
            int pktCount = entry.getValue()[0];
            double rate = pktCount / durationSeconds;
            results.add(new StreamInfo(keys.get(mk), sourceIps.get(mk), pktCount, rate));
        }
        results.sort(Comparator.comparingInt(StreamInfo::key));
        return results;
    }

    // ------------------------------------------------------------------
    // Listeners
    // ------------------------------------------------------------------

    public void addListener(Consumer<Map<String, Double>> l) {
        listeners.add(l);
    }

    /** Remove all registered listeners (used when switching streams). */
    public void clearListeners() {
        listeners.clear();
    }

    private void notifyListeners() {
        Map<String, Double> snap = Map.copyOf(values);
        for (Consumer<Map<String, Double>> l : listeners) {
            l.accept(snap);
        }
    }

    public void stop() {
        running = false;
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
    }

    public Set<String> getVariables() {
        return values.keySet();
    }
}
