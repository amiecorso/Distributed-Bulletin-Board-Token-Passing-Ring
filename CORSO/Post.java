
public class Post extends Packet {
	int seqno;
	String msg;
	
	Post(int srcID, int seqno, String msg) {
		super.type = 0;
		super.srcID = srcID;
		this.seqno = seqno;
		this.msg = msg;
	}
}
