package protocols.dht.kademlia;

import membership.common.ChannelCreated;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import protocols.dht.kademlia.messages.KademliaFindNodeReply;
import protocols.dht.kademlia.messages.KademliaFindNodeRequest;
import protocols.dht.replies.LookupResponse;
import protocols.dht.requests.LookupRequest;
import protocols.storage.Storage;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.*;
import pt.unl.fct.di.novasys.babel.core.GenericProtocol;
import pt.unl.fct.di.novasys.babel.exceptions.HandlerRegistrationException;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;
import pt.unl.fct.di.novasys.channel.tcp.TCPChannel;
import pt.unl.fct.di.novasys.channel.tcp.events.InConnectionDown;
import pt.unl.fct.di.novasys.channel.tcp.events.OutConnectionDown;
import pt.unl.fct.di.novasys.channel.tcp.events.OutConnectionFailed;
import pt.unl.fct.di.novasys.channel.tcp.events.OutConnectionUp;
import pt.unl.fct.di.novasys.network.data.Host;

import utils.HashGenerator;

public class Kademlia extends GenericProtocol {
    private static final Logger logger = LogManager.getLogger(Kademlia.class);

    // Protocol information, to register in babel
    public final static short PROTOCOL_ID = 1100;
    public final static String PROTOCOL_NAME = "kademlia";

    private Map<BigInteger, QueryState> queriesByIdToFind;

    private Node my_node;
    private List<Bucket> k_buckets_list;
    private int alfa, k, channelId;

    private List<Node> conections;
    private List<KademliaFindNodeRequest> waitingForConection;

    public Kademlia(Host self, Properties props) throws HandlerRegistrationException, IOException {
        super(PROTOCOL_NAME, PROTOCOL_ID);
        k_buckets_list = new ArrayList<Bucket>();
        my_node = new Node(self, HashGenerator.generateHash(self.toString()));
        queriesByIdToFind = new HashMap<>();
        alfa = Integer.parseInt(props.getProperty("alfaValue"));
        k = Integer.parseInt(props.getProperty("kValue"));
        conections = new ArrayList<>();

        String cMetricsInterval = props.getProperty("channel_metrics_interval", "10000"); // 10 seconds

        // Create a properties object to setup channel-specific properties. See the
        // channel description for more details.
        Properties channelProps = new Properties();
        channelProps.setProperty(TCPChannel.ADDRESS_KEY, props.getProperty("address")); // The address to bind to
        channelProps.setProperty(TCPChannel.PORT_KEY, props.getProperty("port")); // The port to bind to
        channelProps.setProperty(TCPChannel.METRICS_INTERVAL_KEY, cMetricsInterval); // The interval to receive channel
                                                                                     // metrics
        channelProps.setProperty(TCPChannel.HEARTBEAT_INTERVAL_KEY, "3000"); // Heartbeats interval for established
                                                                             // connections
        channelProps.setProperty(TCPChannel.HEARTBEAT_TOLERANCE_KEY, "3000"); // Time passed without heartbeats until
                                                                              // closing a connection
        channelProps.setProperty(TCPChannel.CONNECT_TIMEOUT_KEY, "1000"); // TCP connect timeout
        channelId = createChannel(TCPChannel.NAME, channelProps); // Create the channel with the given properties

        /*----------------------------- Register Message Handlers ----------------------------- */
        registerMessageHandler(channelId, KademliaFindNodeRequest.MESSAGE_ID, this::uponFindNode, this::uponMsgFail);
        registerMessageHandler(channelId, KademliaFindNodeReply.MESSAGE_ID, this::uponFindNodeReply, this::uponMsgFail);

        /*--------------------- Register Message Serializers ----------------------------- */
        registerMessageSerializer(channelId, KademliaFindNodeRequest.MESSAGE_ID, KademliaFindNodeRequest.serializer);
        registerMessageSerializer(channelId, KademliaFindNodeReply.MESSAGE_ID, KademliaFindNodeReply.serializer);

        /*----------------------------- Register Request Handlers ----------------------------- */
        registerRequestHandler(LookupRequest.REQUEST_ID, this::uponLookupRequest);

        /*------------------------------------- TCPEvents ------------------------------------- */
        registerChannelEventHandler(channelId, OutConnectionUp.EVENT_ID, this::uponOutConnectionUp);
        registerChannelEventHandler(channelId, OutConnectionDown.EVENT_ID, this::uponOutConnectionDown);
        registerChannelEventHandler(channelId, OutConnectionFailed.EVENT_ID, this::uponOutConnectionFailed);
        registerChannelEventHandler(channelId, InConnectionDown.EVENT_ID, this::uponInConnectionDown);
    }

    @Override
    public void init(Properties properties) throws HandlerRegistrationException, IOException {
        triggerNotification(new ChannelCreated(channelId));
        String contact = properties.getProperty("contact");
        if (contact != "null") {
            try {
                String[] hostElems = contact.split(":");
                Host contactHost = new Host(InetAddress.getByName(hostElems[0]), Short.parseShort(hostElems[1]));
                //openConnection(contactHost); tirei porque senão ia fazer duas vezes
                insert_on_k_bucket(new Node(contactHost, HashGenerator.generateHash(contactHost.toString()))); 
                node_lookup(my_node.getNodeId(), null);
            } catch (Exception e) {
                logger.error("Invalid contact on configuration: '" + properties.getProperty("contact"));
                System.exit(-1);
            }
        }
        
        Bucket b = new Bucket(new BigInteger("0"), BigInteger.ZERO.setBit(160).subtract(BigInteger.ONE));
        b.addNode(my_node);
        k_buckets_list.add(b);
       
    }

    /* --------------------------------- Messages ---------------------------- */
    private void uponMsgFail(ProtoMessage msg, Host host, short destProto, Throwable throwable, int channelId) {

    }

    private void uponFindNode(KademliaFindNodeRequest msg, Host host, short destProto, int channelId) {
        List<Node> closest_nodes = find_node(msg.getIdToFind());
        insert_on_k_bucket(msg.getSender());
        KademliaFindNodeReply reply = new KademliaFindNodeReply(msg.getUid(), closest_nodes, msg.getIdToFind(),
                my_node);
        sendMessage(reply, msg.getSender().getHost());
    }

    private void uponFindNodeReply(KademliaFindNodeReply msg, Host host, short sourceProto, int channelId) {
        BigInteger idToFind = msg.getIdToFind();

        QueryState query = queriesByIdToFind.get(idToFind);
        query.receivedFindNodeReply(msg.getSender());

        List<Node> kclosestReturned = msg.getClosestNodes();
        for (int i = 0; i < k; i++) {
            insert_on_k_bucket(kclosestReturned.get(i));
            query.updateKclosest(kclosestReturned.get(i), idToFind);
        }

        if (query.hasStabilised() && msg.getUid() != null) { // encontrei os knodes mais proximos
            LookupResponse reply = new LookupResponse(msg.getUid(), msg.getIdToFind(), query.getKHosts());
            sendReply(reply, Storage.PROTOCOL_ID);
            queriesByIdToFind.remove(idToFind);
        } else if(!query.hasStabilised()){
            List<Node> kclosest = query.getKclosest();
            for (Node n : kclosest) {
                if (!query.alreadyQueried(n) && !query.stillOngoing(n)) { // ainda não contactei com este no
                    KademliaFindNodeRequest req = new KademliaFindNodeRequest(msg.getUid(), idToFind, my_node, n);
                    query.sendFindNodeRequest(n);
                    if(conections.contains(n)) //ja tenho conexao
                        sendMessage(req , n.getHost());
                    else {
                        waitingForConection.add(req);
                        openConnection(n.getHost());
                    }
                }
            }
        }
    }

    /*--------------------------------- Requests ---------------------------------------- */
    private void uponLookupRequest(LookupRequest lookupRequest, short sourceProto) {
        // E se eu tiver um lookup do mesmo ficheiro quase simultaneamente
        node_lookup(lookupRequest.getObjID(), lookupRequest.getRequestUID());
    }

    /*--------------------------------- TCP ---------------------------------------- */
    // If a connection is successfully established, this event is triggered.
    private void uponOutConnectionUp(OutConnectionUp event, int channelId) {
        Host peer = event.getNode();
        logger.info("Out Connection to {} is up.", peer);
        Node n = new Node(peer, HashGenerator.generateHash(peer.toString()));
        conections.add(n);

        for (KademliaFindNodeRequest req: waitingForConection){
            if(req.getDest().equals(n))
                sendMessage(req, peer);
        }       
    }

    private void uponOutConnectionDown(OutConnectionDown event, int channelId) {
        Host peer = event.getNode();
        logger.info("Out Connection to {} is down cause {}", peer, event.getCause());

        Node n = new Node(peer, HashGenerator.generateHash(peer.toString()));
        this.remove_from_k_bucket(n);
        conections.remove(n);
    }

    private void uponOutConnectionFailed(OutConnectionFailed<ProtoMessage> event, int channelId) {
        Host peer = event.getNode();
        logger.info("Connection to {} failed cause: {}", peer, event.getCause());
    }

    // A connection someone established to me is disconnected.
    private void uponInConnectionDown(InConnectionDown event, int channelId) {
        Host peer = event.getNode();
        logger.info("In Connection to {} is down cause {}", peer, event.getCause());

        Node n = new Node(peer, HashGenerator.generateHash(peer.toString()));
        this.remove_from_k_bucket(n);
        conections.remove(n);
    }

    /* --------------------------------- Utils ---------------------------- */
    private void insert_on_k_bucket(Node node) {
        BigInteger distance = calculate_dist(node.getNodeId(), my_node.getNodeId());

        Bucket insert_bucket = getBucket(distance);

        if (insert_bucket.containsNode(node)) { // colocar o no na cauda da lista
            insert_bucket.removeNode(node);
        }
        insert_bucket.addNode(node);

        ArrayList<Node> hosts = insert_bucket.getNodes();
        if(hosts.size() > k  && hosts.contains(my_node)){
            this.divideBucket(insert_bucket);
            logger.info("k_bucket divided.");
        }
        
        logger.info("Node added.");
    }

    private void remove_from_k_bucket(Node node) {
        BigInteger distance = calculate_dist(node.getNodeId(), my_node.getNodeId());

        Bucket bucket_remove = getBucket(distance);

        if (bucket_remove.containsNode(node)){
            bucket_remove.removeNode(node);
        }
    }

    private List<Node> find_node(BigInteger node_id) {
        BigInteger distance = calculate_dist(node_id, my_node.getNodeId());
        
        List<Node> closest_nodes = new ArrayList<Node>(k); 

        Bucket b = getBucket(distance);
        List<Node> nodes = b.getNodes();
        for(Node n: nodes){
            closest_nodes.add(n);
        }
  
        if(closest_nodes.size() < k){
            for(Bucket bucket: k_buckets_list){
                if(b.getMax().compareTo(bucket.getMin().subtract(new BigInteger("1"))) == 0){
                    for(Node node: bucket.getNodes()){
                        closest_nodes.add(node);
                        if(closest_nodes.size() >= k)
                            break;
                    }
                }
            }
        }

        return closest_nodes;
    }

    private void node_lookup(BigInteger id, UUID mid) {
        List<Node> kclosest = find_node(id); // list containing the k closest nodes
        // logger.info("pre null pointer: {}", kclosest.size());
        QueryState query = new QueryState(kclosest);
	
	    logger.info(mid + " dina " + my_node.getHost());
	
        if(kclosest.size() == 1 && kclosest.get(0).equals(my_node)){
            ArrayList<Host> myHost = new ArrayList<>();
            myHost.add(my_node.getHost());
            sendReply(new LookupResponse(mid, id, myHost), Storage.PROTOCOL_ID);
        } else {
            for (int i = 0; i < alfa && i < kclosest.size(); i++) {
                Node n = kclosest.get(i);
                if (!n.equals(my_node)) {
                    KademliaFindNodeRequest msg = new KademliaFindNodeRequest(mid, id, my_node, n);
                    query.sendFindNodeRequest(n);
                    if(conections.contains(n)) //ja tenho conexao
                        sendMessage(msg , n.getHost());
                    else {
                        waitingForConection.add(msg);
                        openConnection(n.getHost());
                    }
                    
                }
    
            }
    
            queriesByIdToFind.put(id, query);
        }   
    }

    private BigInteger calculate_dist(BigInteger node1, BigInteger node2) {
        return node2.xor(node1);
    }

    private Bucket getBucket(BigInteger dist){
        for(Bucket b: k_buckets_list){
            if((dist.compareTo(b.getMax()) == -1 && dist.compareTo(b.getMin()) == 1) 
                || dist.compareTo(b.getMin()) == 0 || dist.compareTo(b.getMax()) == 0){
                	
               	return b;
                }	
            
        }
        
        return null;
    }

    private void divideBucket(Bucket b){
        BigInteger aux =  b.getMax().divide(new BigInteger("2"));
        Bucket new_b = new Bucket(new BigInteger("0"), aux);
        Bucket new_b_2 = new Bucket(aux.add(new BigInteger("1")), b.getMax());

        ArrayList<Node> nodes = b.getNodes();
        for(Node node: nodes){
            BigInteger dist = calculate_dist(node.getNodeId(), this.my_node.getNodeId());
            if(dist.compareTo(aux) == 1)
                new_b_2.addNode(node);
            else
                new_b.addNode(node);
        }

        this.k_buckets_list.remove(b);
        this.k_buckets_list.add(new_b);
        this.k_buckets_list.add(new_b_2);
    }
}