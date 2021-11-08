package protocols.dht.messages;

import java.util.UUID;

import pt.unl.fct.di.novasys.network.data.Host;
import pt.unl.fct.di.novasys.babel.generic.ProtoMessage;

public class KelipsJoinRequest extends ProtoMessage{
    public final static short REQUEST_ID = 1050;
	
	private UUID uid;
    private Host sender;
    private long time;
	
	public KelipsJoinRequest(Host sender) {
		super(REQUEST_ID);
		this.uid = UUID.randomUUID();
        this.sender = sender;
        this.time = System.currentTimeMillis();
	}

    public UUID getUid(){
        return this.uid;
    }

    public Host getHost(){
        return this.sender;
    }
    
    public long getTime(){
        return this.time;
    }

}
