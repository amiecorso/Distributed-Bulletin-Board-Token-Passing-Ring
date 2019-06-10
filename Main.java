import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.Scanner;
import java.util.Random;
import java.util.HashMap;

public class Main {
	static long START = System.currentTimeMillis();
	static long LASTIMPORTANT = 0;
	static PrintWriter WRITER = null;
	static DatagramSocket SOCK = null;
	static int RTT = 200;
	static int TIMEOUT = 2000; // milliseconds
	static int PROBESO_TO = 5; // millis
	static int REGSO_TO = 5; // millis
	static int MAXPROBESO_TOS = 10;
	static int MINTO = 30;
	static String config = null;
	static String input = null;
	static String output = null;
	static int MYPORT;
	static int MINPORT;
	static int MAXPORT;
	static int JOINTIME;
	static int LEAVETIME;
	static int PREV = 0;
	static int NEXT = 0;
	static int TOKENID;
	static boolean IHAVETOKEN = false;
	static Message PENDINGMSG = null;
	static int TOKENTIME; // point of token receipt
	static int CURRENTELECTION;
	static boolean DISCRETURN = false;
	static ArrayList<Integer> PORTS = new ArrayList<Integer>();
	static Packet PENDING = null;
	static long PENDINGSENT = 0;
	static PriorityQueue<Message> MESSAGES = new PriorityQueue<Message>();
	static HashMap<Integer, Integer> DUPDETECTOR = new HashMap<Integer, Integer>();
	
	public static void main(String[] args) {
		parseInputs(args);
		populatePorts();
		try {
			WRITER = new PrintWriter(output, "UTF-8");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		// SLEEP TILL JOINTIME
		try {
			Thread.sleep(JOINTIME * 1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}		
		// SOCKET SETUP
		try {
			SOCK = new DatagramSocket(MYPORT);
			SOCK.setSoTimeout(PROBESO_TO); // very short timeout
		}
		catch(SocketException ex) {
			System.out.println("Failed to bind socket");
		}
		// INITIAL DISCOVERY
		DISCRETURN = discover();
		LASTIMPORTANT = elapsedSinceStartMillis();
		if (DISCRETURN) {
			return;
		}
		prettyP("EXITING INITIAL DISCOVER", new Throwable().getStackTrace()[0].getMethodName());
		startElection();
		// MAIN LOOP
		while (elapsedSinceStart() < LEAVETIME) {
			while ((elapsedSinceStart() < LEAVETIME) && ((elapsedSinceStartMillis() - LASTIMPORTANT) < TIMEOUT)) {
				try {
					Tuple t = recvObj();
					Packet p = (Packet) t.packet;
					int port = t.port;
					handlePacket(p, port);
					//TIMEOUT = 2000;
				}
				catch(SocketTimeoutException ex) {
					//TIMEOUT = 2000;
				}	
			} // end while not timed out
			writeToLog("ring is broken", new Throwable().getStackTrace()[0].getMethodName());
			if (PENDINGMSG != null) { // ONLY if we have one
				if (MESSAGES.peek() != null) {
					if (MESSAGES.peek().seqno != PENDINGMSG.seqno) {
						MESSAGES.add(PENDINGMSG);
					}
				}
				else {  // MESSAGES is empty, so just put it back
					MESSAGES.add(PENDINGMSG);
				}
			}
			DISCRETURN = discover();
			LASTIMPORTANT = elapsedSinceStartMillis();

			if (DISCRETURN) {
				return;
			}
			prettyP("EXITING DISCOVER", new Throwable().getStackTrace()[0].getMethodName());
			startElection();
		}// end while not leave time
	} // end int main
	
    
    public static void handlePacket(Packet p, int port) {
		if ((p.type != 10) && (port != PREV)) {
			//prettyP(String.format("Received packet w/srcID=%d, from port=%d: My PREV=%d, dropping...", p.srcID, port, PREV), new Throwable().getStackTrace()[0].getMethodName());
			//prettyP(String.format("%b", port != PREV), "handlepacket");
			return;
		}
		switch(p.type) {
		case 0: // POST
			LASTIMPORTANT = elapsedSinceStartMillis();

			prettyP(String.format("Received a POST message from %d.", p.srcID), new Throwable().getStackTrace()[0].getMethodName());
			Post post = (Post) p;
			updateTimer(post, elapsedSinceStartMillis());
			if ((post.srcID == MYPORT) && (post.seqno == PENDINGMSG.seqno)) { // my pending msg
				PENDINGMSG = null;
				writeToLog(String.format("post \"%s\" was delivered to all successfully", post.msg), new Throwable().getStackTrace()[0].getMethodName());
				if (MESSAGES.peek() != null) {
					if(MESSAGES.peek().sendtime <= TOKENTIME) {
						Message m = MESSAGES.poll();
						Post postm = new Post(MYPORT, m.seqno, m.body);
				    	PENDING = (Packet) postm;
				    	PENDINGSENT = elapsedSinceStartMillis();
						sendObj(postm, NEXT);
						writeToLog(String.format("post \"%s\" was sent", postm.msg), new Throwable().getStackTrace()[0].getMethodName());
						PENDINGMSG = m;
					}
					else { // no more pending messages
						if (IHAVETOKEN) { // forward it
							fwdToken();
						}		
					}
				} // end if peek != null
				else { // peek is null
					fwdToken();
				}
			}
			else { // not my pending message, just forward
				sendObj(post, NEXT);
				writeToLog(String.format("post \"%s\" from client %d was relayed", post.msg, post.srcID), new Throwable().getStackTrace()[0].getMethodName());
			}
			break;
		case 10: // PROBE
			LASTIMPORTANT = elapsedSinceStartMillis();

			prettyP(String.format("Received a PROBE message from %d.", p.srcID), new Throwable().getStackTrace()[0].getMethodName());
			Probe probe = (Probe) p;
			decidePrev(probe);
			//if (NEXT ==  0) {
			//	DISCRETURN = discover();
			//}
			prettyP("decided prev, next statement should be break", "handlePacket");
			break;
		case 11: // PROBEACK
			prettyP(String.format("Received a PROBEACK message from %d.", p.srcID), new Throwable().getStackTrace()[0].getMethodName());
			prettyP(String.format("not in discovery, dropping PROBEACK"), new Throwable().getStackTrace()[0].getMethodName());
			break;
		case 12: // PROBENACK
			prettyP(String.format("Received a PROBENACK message from %d.", p.srcID), new Throwable().getStackTrace()[0].getMethodName());
			prettyP(String.format("not in discovery, dropping PROBENACK"), new Throwable().getStackTrace()[0].getMethodName());
			break;
		case 20: // ELECTION
			prettyP(String.format("Received an ELECTION message from client:%d port:%d.", p.srcID, port), new Throwable().getStackTrace()[0].getMethodName());
			Election election = (Election) p;
			handleElection(election);
			break;
		case 21: // ELECTED				
			prettyP(String.format("Received an ELECTED message from client:%d, port:%d.", p.srcID, port), new Throwable().getStackTrace()[0].getMethodName());
			Elected elected = (Elected) p;
			handleElected(elected);
			break;
		case 30: // TOKEN
			
			//prettyP(String.format("Received a TOKEN message from %d.", p.srcID), new Throwable().getStackTrace()[0].getMethodName());
			Token token = (Token) p;
			if (token.tokenID != TOKENID) { // invalid token
				prettyP(String.format("Received INVALID TOKEN from %d",  p.srcID), new Throwable().getStackTrace()[0].getMethodName());
				break;
			}
			LASTIMPORTANT = elapsedSinceStartMillis();

			IHAVETOKEN = true;
			//writeToLog(String.format("token %d was received", token.tokenID), new Throwable().getStackTrace()[0].getMethodName());
			TOKENTIME = elapsedSinceStart();
			
			if (MESSAGES.peek() != null) {
				//prettyP("Messages.peek() not null", "handlepacket");
				//prettyP(String.format("Messages.peek().sendtime = %d", MESSAGES.peek().sendtime), "handlepacket");
				if (MESSAGES.peek().sendtime <= TOKENTIME) {
					Message m = MESSAGES.poll();
					Post postm = new Post(MYPORT, m.seqno, m.body);
			    	//Timestamp stamp = new Timestamp(postm, elapsedSinceStartMillis());
			    	PENDING = (Packet) postm;
			    	PENDINGSENT = elapsedSinceStartMillis();
					sendObj(postm, NEXT);
					writeToLog(String.format("post \"%s\" was sent", postm.msg), new Throwable().getStackTrace()[0].getMethodName());
					PENDINGMSG = m;
				}
				else { // if we DON'T have any with low timestamp, fwd
					fwdToken();
				}
			}
			else { // no more msgs at all
				fwdToken();
			}
			break;
		default: // UNKNOWN
			prettyP(String.format("Received UNKNOWN MESSAGE TYPE", MYPORT), new Throwable().getStackTrace()[0].getMethodName());
			break;
		}
	}
    
    public static void setSOTO(int timeout) {
		try {
			SOCK.setSoTimeout(timeout);
		} catch (SocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} // very short timeout
    }
    
    public static boolean discover() {
    	setSOTO(PROBESO_TO);
    	TIMEOUT = 2000;
    	NEXT = 0;
		prettyP(String.format("called discover"), new Throwable().getStackTrace()[0].getMethodName());
		int tryindex = 1;
		while ((elapsedSinceStart() < LEAVETIME) && (NEXT == 0)) {
			int nexttry = PORTS.get(tryindex);
			// TODO: figure out what this msgID field in a probe message is for
			Probe pro = new Probe(MYPORT, tryindex);
			sendObj(pro, nexttry);
			prettyP(String.format("PROBING %d", nexttry), new Throwable().getStackTrace()[0].getMethodName());
			int to_count = 0;
			while (to_count < MAXPROBESO_TOS) {
				try {
					Packet proberesponse = (Packet) recvObj().packet;
					switch (proberesponse.type) {
					case 10: // probe
						prettyP(String.format("Received a PROBE from %d.", proberesponse.srcID), new Throwable().getStackTrace()[0].getMethodName());
						Probe probe = (Probe) proberesponse;
						decidePrev(probe);
						if (DISCRETURN) {
							return true;
						}
						break;
					case 11: // probeAck
						prettyP(String.format("Received a PROBEACK from %d.", proberesponse.srcID), new Throwable().getStackTrace()[0].getMethodName());
						ProbeAck probeack = (ProbeAck) proberesponse;
						NEXT = probeack.srcID;
						writeToLog(String.format("next hop is changed to client %d", probeack.srcID), new Throwable().getStackTrace()[0].getMethodName());
						prettyP("RETURNING FROM DISCOVER", "discover");
				    	setSOTO(REGSO_TO);
						return false; // we got our next-hop!
						//break;
					case 12: // probeNack
						prettyP(String.format("Received a PROBENACK from %d.", proberesponse.srcID), new Throwable().getStackTrace()[0].getMethodName());
						ProbeNack probenack = (ProbeNack) proberesponse;
						if ((probenack.srcID == nexttry) && (probenack.msgID == tryindex)) {
							to_count = MAXPROBESO_TOS; // will cause us to continue
						}
						break;
					default: // any other type, can't deal with right now, drop
						prettyP(String.format("Dropping type %d from %d, still in discover...", proberesponse.type, proberesponse.srcID), new Throwable().getStackTrace()[0].getMethodName());
						break;
					}	// end switch
				} // end try
				catch (SocketTimeoutException ex) {
					to_count += 1;
					//System.out.println(String.format("Socket timeout num %d at client %d during discovery", to_count, MYPORT));
				}
			} // end inner while
			//prettyP(String.format("timed out probing %d", nexttry), new Throwable().getStackTrace()[0].getMethodName());
			tryindex = (tryindex + 1) % PORTS.size();
		} // end while Next == 0 && not leavetime
    	setSOTO(REGSO_TO);
    	if (NEXT == 0) {
    		return true;
    	}
    	return false;
	}


	public static void decidePrev(Probe probe) {
		int candidate = probe.srcID;
		prettyP(String.format("Deciding Prev: currPREV: %d, candidate: %d", PREV, candidate), new Throwable().getStackTrace()[0].getMethodName());
		if (PREV == 0){
			PREV = candidate;
			writeToLog(String.format("previous hop is changed to client %d", candidate), new Throwable().getStackTrace()[0].getMethodName());
			ProbeAck ack = new ProbeAck(MYPORT, probe.msgID); // TODO: probeack msg id??
			sendObj(ack, candidate);
			prettyP(String.format("ACKING %d", candidate), new Throwable().getStackTrace()[0].getMethodName());
			return;
		}
		if (PREV == MYPORT) {
			PREV = candidate;
			if (candidate != MYPORT) {
				writeToLog(String.format("previous hop is changed to client %d", candidate), new Throwable().getStackTrace()[0].getMethodName());
			}
			ProbeAck ack = new ProbeAck(MYPORT, probe.msgID); // TODO: probeack msg id??
			sendObj(ack, candidate);
			prettyP(String.format("ACKING %d", candidate), new Throwable().getStackTrace()[0].getMethodName());
			NEXT = 0; // if we were alone, we need to find this node that just connected to us
			DISCRETURN = discover(); 
			LASTIMPORTANT = elapsedSinceStartMillis();

			//startElection();
			return;
		}
		if (PREV == candidate) {
			ProbeAck ack = new ProbeAck(MYPORT, probe.msgID); // TODO: probeack msg id??
			sendObj(ack, candidate);
			prettyP(String.format("ACKING %d", candidate), new Throwable().getStackTrace()[0].getMethodName());
			return;
		}
		// else
		PREV = candidate;
		writeToLog(String.format("previous hop is changed to client %d", candidate), new Throwable().getStackTrace()[0].getMethodName());
		ProbeAck ack = new ProbeAck(MYPORT, probe.msgID); // TODO: probeack msg id??
		sendObj(ack, candidate);
		prettyP(String.format("ACKING %d", candidate), new Throwable().getStackTrace()[0].getMethodName());
		//ProbeNack nack = new ProbeNack(MYPORT, probe.msgID); // TODO: probenack msg id??
		//sendObj(nack, candidate);
		///prettyP(String.format("Nacking %d", candidate), new Throwable().getStackTrace()[0].getMethodName());
	}


	public static void startElection() {
		prettyP("entered startElection... waiting", "startElection");
		CURRENTELECTION = -1;
		Random random = new Random();
		int waitmillis = random.nextInt(1000);
		long now = elapsedSinceStartMillis();
		while (elapsedSinceStartMillis() - now < waitmillis) {
			Tuple t;
			try {
				t = recvObj();
				Packet p = (Packet) t.packet;
				int port = t.port;
				if ((p.type != 10) && (port != PREV)) {
					prettyP(String.format("Received packet from node %d , NOT PREV (%d), dropping...", p.srcID, PREV), new Throwable().getStackTrace()[0].getMethodName());
					continue;
				}
				switch(p.type) {
				case 0: // POST
					prettyP(String.format("Received a POST message from %d.", p.srcID), new Throwable().getStackTrace()[0].getMethodName());
					Post post = (Post) p;
					if (post.srcID != MYPORT) {
						sendObj(post, NEXT);
						writeToLog(String.format("post \"%s\" from client %d was relayed", post.msg, post.srcID), new Throwable().getStackTrace()[0].getMethodName());
					}
					break;
				case 10: // PROBE
					prettyP(String.format("Received a PROBE message from %d.", p.srcID), new Throwable().getStackTrace()[0].getMethodName());
					Probe probe = (Probe) p;
					decidePrev(probe);
					break;
				case 11: // PROBEACK
					prettyP(String.format("Received a PROBEACK message from %d.", p.srcID), new Throwable().getStackTrace()[0].getMethodName());
					prettyP(String.format("not in discovery, dropping PROBEACK"), new Throwable().getStackTrace()[0].getMethodName());
					break;
				case 12: // PROBENACK
					prettyP(String.format("Received a PROBENACK message from %d.", p.srcID), new Throwable().getStackTrace()[0].getMethodName());
					prettyP(String.format("not in discovery, dropping PROBENACK"), new Throwable().getStackTrace()[0].getMethodName());
					break;
				case 20: // ELECTION
					prettyP(String.format("Received an ELECTION message from %d.", p.srcID), new Throwable().getStackTrace()[0].getMethodName());
					Election election = (Election) p;
					handleElection(election);
					return; // we no longer need to start one
				case 21: // ELECTED				
					prettyP(String.format("Received an ELECTED message from %d.", p.srcID), new Throwable().getStackTrace()[0].getMethodName());
					Elected elected = (Elected) p;
					handleElected(elected);
					break;
				case 30: // TOKEN
					prettyP(String.format("Received a TOKEN message from %d in startElection, dropping!!.", p.srcID), new Throwable().getStackTrace()[0].getMethodName());
					break;
				default: // UNKNOWN
					prettyP(String.format("Received UNKNOWN MESSAGE TYPE", MYPORT), new Throwable().getStackTrace()[0].getMethodName());
					break;
				}
			} catch (SocketTimeoutException e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
			}
	
		} // end while
		prettyP("wait is up! starting election", "startElection");
		// Wait is up - start the election
	    int electionID = random.nextInt(100000);
		Election election = new Election(MYPORT, electionID, MYPORT);
		CURRENTELECTION = electionID;
		sendObj(election, NEXT);
		writeToLog(String.format("started election, send election %d message to client %d", electionID, NEXT), new Throwable().getStackTrace()[0].getMethodName());
	}


	public static void handleElection(Election election) {
		TOKENID = -1; // invalidate token upon occurrence of election
		CURRENTELECTION = election.electionID;
		LASTIMPORTANT = elapsedSinceStartMillis();
		prettyP(String.format("received election msg ID=%d", election.electionID), "handleElection");
		if (MYPORT > election.bestSoFar) {
			election.bestSoFar = MYPORT;
			sendObj(election, NEXT);
			writeToLog(String.format("relayed election message %d, replaced leader", election.electionID), new Throwable().getStackTrace()[0].getMethodName());
			return;
		}
		if (MYPORT < election.bestSoFar) {
			sendObj(election, NEXT);
			writeToLog(String.format("relayed election message %d, leader: client %d", election.electionID, NEXT, election.bestSoFar), new Throwable().getStackTrace()[0].getMethodName());
			return;
		}
		if (MYPORT == election.bestSoFar) {
			Elected elected = new Elected(election.srcID, election.electionID, MYPORT);
			sendObj(elected, NEXT);
			prettyP(String.format("Sending elected msg: srcID=%d, electionID=%d, MYPORT=%d to %d", election.srcID, election.electionID, MYPORT, NEXT), "handleElection");
			writeToLog(String.format("leader selected electionid=%d", election.electionID), new Throwable().getStackTrace()[0].getMethodName());
			return;
		}
		
	}


	public static void handleElected(Elected elected) {
		prettyP("RECEIVED ELECTED MSG", "handleElected");
		if (elected.electedClient < MYPORT) {
			prettyP(String.format("elected client %d less than my port %d, dropping", elected.electedClient, MYPORT), "handleElection");
			return; // don't forward
			// TODO: do we need to start a new election now or anything? we probably already did if we just got here...
		}
		if (elected.electionID != CURRENTELECTION) {
			prettyP(String.format("election ID %d != my current electionid %d, dropping", elected.electionID, CURRENTELECTION), "handleElection");
			return; // don't forward - there is a more recent election happening
		}
		LASTIMPORTANT = elapsedSinceStartMillis();

		TOKENID = elected.electionID;
		if (elected.electedClient == MYPORT) { // I'm elected!
			TOKENTIME = elapsedSinceStart();
			IHAVETOKEN = true;
			writeToLog(String.format("new token generated %d", TOKENID), new Throwable().getStackTrace()[0].getMethodName());
			// send all my msgs (i "have" the token)
			if ((MESSAGES.peek() != null) && (MESSAGES.peek().sendtime <= TOKENTIME)) {
				Message m = MESSAGES.poll();
				Post postm = new Post(MYPORT, m.seqno, m.body);
		    	PENDING = (Packet) postm;
		    	PENDINGSENT = elapsedSinceStartMillis();
				sendObj(postm, NEXT);
				writeToLog(String.format("post \"%s\" was sent", postm.msg), new Throwable().getStackTrace()[0].getMethodName());
				PENDINGMSG = m;
			}
			else {
				Token tok = new Token(MYPORT, TOKENID);
				sendObj(tok, NEXT);
				//writeToLog(String.format("token %d was sent to client %d", TOKENID, NEXT), new Throwable().getStackTrace()[0].getMethodName());
			}
		} // endif I'M elected
		else { // i am not the elected one, just forward
			sendObj(elected, NEXT);
		}
		
	}

	public static void timerAux(long elapsed) {
		int RTTfactor = 10;
		int newtimeout;
		
		RTT = (int) (elapsed - PENDINGSENT);
		newtimeout = RTTfactor * RTT;
		
		if (newtimeout > 2000) {
			TIMEOUT = 2000;
		}
		else {
			TIMEOUT = newtimeout;
			if (TIMEOUT < MINTO) {
				TIMEOUT = MINTO;
			}
		}
		prettyP(String.format("TIMEOUT=%d", TIMEOUT), new Throwable().getStackTrace()[0].getMethodName());
		return;			
	}
	
	public static void updateTimer(Packet p, long elapsed) {
		Post post = (Post) p;
		if ((PENDING != null) && (((Post) PENDING).seqno == post.seqno)) {
			timerAux(elapsed);
		}
	}

	public static void fwdToken() {
		IHAVETOKEN = false;
		Token tok = new Token(MYPORT, TOKENID);
		sendObj(tok, NEXT);
		//writeToLog(String.format("token %d was sent to client %d", TOKENID, NEXT), new Throwable().getStackTrace()[0].getMethodName());
    }

	public static void writeToLog(String msg, String method) {
		int time = elapsedSinceStart();
		int minutes = time / 60;
		int seconds = time % 60;
		String output = String.format("%d:%d: %s\n", minutes, seconds, msg);
		WRITER.write(output);
		WRITER.flush();
		System.out.println(output);
		//prettyP(String.format("NEXT=%d, PREV=%d : %s", NEXT, PREV, output), method);
	}

	public static void prettyP(String msg, String method) {
		int linenum = new Exception().getStackTrace()[0].getLineNumber();
		int time = elapsedSinceStart();
		int minutes = time / 60;
		int seconds = time % 60;
		String stime = String.format("%d:%d", minutes, seconds);
		if (method.contentEquals("discover")) {
			//String formatted = String.format("%d: %s, %s (PREV=%d, NEXT=%d): %s", MYPORT, stime, method, PREV, NEXT, msg);
			//System.out.println(formatted);
			//WRITER.write(formatted + "\n");
			//WRITER.flush();
			return;
		}
		//System.out.println(String.format("%d: %s, %s: %s", MYPORT, stime, method, msg));
	}

	public static void sendObj(Object o, int desPort) {
    	ByteArrayOutputStream byteStream = new ByteArrayOutputStream(5000);
    	try {
			ObjectOutputStream os = new ObjectOutputStream(new BufferedOutputStream(byteStream));
			os.flush();
			os.writeObject(o);
			os.flush();
			//retrieve byte array
			byte[] sendBuf = byteStream.toByteArray();
			DatagramPacket packet = new DatagramPacket(sendBuf, sendBuf.length);
	        // Set the destination host and port 
	        packet.setAddress(InetAddress.getByName("localhost"));
	        packet.setPort(desPort);
			//int byteCount = packet.getLength();
			SOCK.send(packet);
			os.close();
    	
    	} catch (IOException e) {
		    System.err.println("Exception:  " + e);
			e.printStackTrace();
		}
    } // end function sendObj
    
    public static Tuple recvObj()  throws SocketTimeoutException {    
    	try {
          byte[] recvBuf = new byte[5000];
          DatagramPacket packet = new DatagramPacket(recvBuf,
                                                     recvBuf.length);
          SOCK.receive(packet);
          int port = packet.getPort();
          ByteArrayInputStream byteStream = new ByteArrayInputStream(recvBuf);
          ObjectInputStream is = new ObjectInputStream(new BufferedInputStream(byteStream));
          Object o = is.readObject();
          is.close();
          return(new Tuple(port, o));
        } 
    	catch (SocketTimeoutException e) {
    		throw e;
    	}
    	catch (IOException e) {
          System.err.println("Exception:  " + e);
          e.printStackTrace();
        }
        catch (ClassNotFoundException e)
        { e.printStackTrace(); }
        return(null);  
     } // end function recvObj
    
    public static void parseInputs(String[] args) {
		// PARSE COMMAND LINE
		if (args.length < 6) {
			System.out.println("usage: -c configfile -i inputfile -o outputfile");
			System.exit(1);
		}
		// -c config file
		String cflag = args[0];
		if (!cflag.contentEquals("-c")) {
			System.out.println("usage: -c configfile -i inputfile -o outputfile");
			System.exit(1);
		}
		else {
			config = args[1];
		}
		// -i input file
		String iflag = args[2];
		if (!iflag.contentEquals("-i")) {
			System.out.println("usage: -c configfile -i inputfile -o outputfile");
			System.exit(1);
		}
		else {
			input = args[3];
		}
		// -o output file
		String oflag = args[4];
		if (!oflag.contentEquals("-o")){
			System.out.println("usage: -c configfile -i inputfile -o outputfile");
			System.exit(1);
		}
		else {
			output = args[5];
		}
		// OPEN FILES
		Scanner configScan = null;
		Scanner inputScan = null;
		try { // CONFIG
			configScan = new Scanner(new File(config));
		}
		catch(FileNotFoundException ex) {
			System.out.println("Unable to open file '" + config + "' for reading");
			System.exit(1);
		}
		try { // INPUT
			inputScan = new Scanner(new File(input));
		}
		catch(FileNotFoundException ex) {
			System.out.println("Unable to open file '" + input + "' for reading");
			System.exit(1);
		}
		String [] splitline = null;
		try { // PARSE CONFIG
			splitline = configScan.nextLine().split(" ");
			String [] ports = splitline[1].split("-");
			MINPORT = Integer.parseInt(ports[0]);
			MAXPORT = Integer.parseInt(ports[1]);
			splitline = configScan.nextLine().split(" ");
			MYPORT = Integer.parseInt(splitline[1]);
			splitline = configScan.nextLine().split(" ");
			String [] stringtime = splitline[1].split(":");
			JOINTIME = Integer.parseInt(stringtime[0]) * 60 + Integer.parseInt(stringtime[1]);
			splitline = configScan.nextLine().split(" ");
			stringtime = splitline[1].split(":");
			LEAVETIME = Integer.parseInt(stringtime[0]) * 60 + Integer.parseInt(stringtime[1]);
			System.out.println("MINPORT = " + MINPORT);
			System.out.println("MAXPORT = " + MAXPORT);
			System.out.println("MYPORT = " + MYPORT);
			System.out.println("JOINTIME = " + JOINTIME);
			System.out.println("LEAVETIME = " + LEAVETIME);
		}
		catch(Exception ex) {
			ex.printStackTrace();
			System.out.println("Error parsing configuration file");
			System.exit(1);
		}
		try { // PARSE INPUT
			int seqno = 0;
			while (inputScan.hasNextLine()) {
				String line = inputScan.nextLine();
				splitline = line.split("\t");
				int minutes = Integer.parseInt(splitline[0].split(":")[0]);
				int seconds = Integer.parseInt(splitline[0].split(":")[1]);
				Message msg = new Message(minutes, seconds, splitline[1], seqno);
				MESSAGES.add(msg);
				seqno += 1;
			}
			Iterator<Message> pqiter = MESSAGES.iterator();
			while (pqiter.hasNext()) {
				System.out.println(pqiter.next());
			}
		}
		catch(Exception ex) {
			ex.printStackTrace();
			System.out.println("Error parsing input file");
			System.exit(1);
		}
    	
    } // end function parseInputs
    
    public static void populatePorts() {
    	for (int i = MYPORT; i <= MAXPORT; i++) {
    		PORTS.add(i);
    	}
    	for (int i = MINPORT; i < MYPORT; i++) {
    		PORTS.add(i);
    	}
    } // end function populatePorts
    
    public static int elapsedSinceStart() {
    	return (int) ((System.currentTimeMillis() - START) / 1000);
    }
    
    public static long elapsedSinceStartMillis() {
    	return (System.currentTimeMillis() - START);
    }

} // end class Main
