package ftp17;

import static ftp17.Ftp17Packet.*;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;


public class Ftp17Client {
	static final int DEFAULT_TIMEOUT = 1000;
	static final int DEFAULT_MAX_RETRIES = 7;
	private static final int DEFAULT_BLOCK_SIZE = 1024;
	static int WindowSize;
	static int BlockSize = DEFAULT_BLOCK_SIZE;
	static int Timeout = DEFAULT_TIMEOUT;


	private Stats stats;
	private String filename;
	private DatagramSocket socket;
	private BlockingQueue<Ftp17Packet> receiverQueue;
	volatile private SocketAddress srvAddress;

	private SortedMap<Long, Ftp17Packet> window;
	private int windowResize = 1;

	private List<Long> PacketsSent;
	private List<Long> PacketsSentTime;

	long byteCount = 1;
	private long Time;
	private long acks;
	private boolean finished;



	Ftp17Client(String filename, SocketAddress srvAddress) {
		this.filename = filename;
		this.srvAddress = srvAddress;
		window = new TreeMap <Long, Ftp17Packet>();
		PacketsSent = new ArrayList<Long>(WindowSize);
		PacketsSentTime = new ArrayList<Long>(WindowSize);
		WindowSize = 5;

	}

	void sendFile() {
		try {

			// socket = new MyDatagramSocket();
			socket = new DatagramSocket();

			// create producer/consumer queue for ACKs
			receiverQueue = new ArrayBlockingQueue<>(1);
			// for statistics
			stats = new Stats();

			// start a receiver process to feed the queue
			new Thread(() -> {
				try {
					for (;;) {
						byte[] buffer = new byte[Ftp17Packet.MAX_FTP17_PACKET_SIZE];
						DatagramPacket msg = new DatagramPacket(buffer, buffer.length);
						socket.receive(msg);
						// update server address (it may change due to reply to UPLOAD coming from a different port
						srvAddress = msg.getSocketAddress();
						System.err.println(new Ftp17Packet(msg.getData(), msg.getLength()) + "from:" + msg.getSocketAddress() );
						// make the packet available to sender process
						Ftp17Packet pkt = new Ftp17Packet(msg.getData(), msg.getLength());
						receiverQueue.put(pkt);
					}
				} catch (Exception e) {
				}
			}).start();

			System.out.println("\nsending file: \"" + filename + "\" to server: " + srvAddress + " from local port:"
					+ socket.getLocalPort() + "\n");


			sendRetry(new UploadPacket(filename), 1L, DEFAULT_MAX_RETRIES);

			try {

				FileInputStream f = new FileInputStream(filename);
				long nextByte = 1L;
				while (f.available()>0 || !window.isEmpty()) {

					int n;
					byte[] buffer = new byte[BlockSize];

					try {

						while(window.size() < WindowSize ) {
							//fills all the freeSlots
							if ((n = f.read(buffer)) > 0) {
								Ftp17Packet pkt = new DataPacket(nextByte, buffer, n);
								nextByte += n;
								stats.newPacketSent(n);
								window.put(nextByte, pkt);


							}
							else

								break;
						}

						//send data from window
						sendWindow();
                //handle acks received
						handleACK(byteCount, DEFAULT_MAX_RETRIES);	




					}
					catch (Exception e) {
						e.printStackTrace();
					}



				}



				// send the FIN packet
				System.err.println("sending: " + new FinPacket(nextByte));
				socket.send(new FinPacket(nextByte).toDatagram(srvAddress));
				finished=true;
				handleACK(nextByte, DEFAULT_MAX_RETRIES);
				// ignore treatment of FINACK 
				f.close();
				//System.exit(0);

			} catch (Exception e) {
				System.err.println("failed with error \n" + e.getMessage());
				e.printStackTrace();
				System.exit(0);
			}
			socket.close();
			System.out.println("Done...");
		} catch (Exception x) {
			x.printStackTrace();
			System.exit(0);
		}
		stats.printReport();
	}

	/*
	 * Send a block to the server, repeating until the expected ACK is received, or
	 * the number of allowed retries is exceeded.
	 */
	void sendRetry(Ftp17Packet pkt, long expectedACK, int retries) throws Exception {
		for (int i = 0; i < retries; i++) {
			System.err.println("sending: " + pkt);
			long sendTime = System.currentTimeMillis();

			socket.send( pkt.toDatagram( srvAddress ));

			Ftp17Packet ack = receiverQueue.poll(Timeout, TimeUnit.MILLISECONDS);
			if (ack != null) {
				if (ack.getOpcode() == ACK)
					if (expectedACK == ack.getSeqN()) {
						stats.newTimeoutMeasure(System.currentTimeMillis() - sendTime);
						System.err.println("got expected ack: " + expectedACK);
						return;
					} else {
						System.err.println("got wrong ack");
					}
				else {
					System.err.println("got unexpected packet (error)");
				}
			}
			else
				System.err.println("timeout...");
		}
		throw new IOException("too many retries");
	}

	void sendWindow() throws IOException {

		for(Long seq : window.keySet()) {

			Ftp17Packet pkt = window.get(seq);

			if(!PacketsSent.contains(seq)){

				System.err.println("sending: " + (pkt.getSeqN()) + " expecting:" + (seq));

				socket.send(pkt.toDatagram( srvAddress ));
				PacketsSent.add(seq);
				PacketsSentTime.add(System.currentTimeMillis());
			}



		}
	}


	/*
	 * * Move window after receiving an acknowledge
	 */
	void handleSlide(long seqNumber) {
		int p = 0;
		while(p < PacketsSent.size()) {

			if(seqNumber >= PacketsSent.get(p)){

				window.remove(PacketsSent.get(p));
				PacketsSent.remove(p);

				stats.newTimeoutMeasure(System.currentTimeMillis() - PacketsSentTime.get(p));
				PacketsSentTime.remove(p);
			}
			p++;

		}


	}

	void handleACK(long expectedACK, int retries) throws Exception {

		Ftp17Packet ack = receiverQueue.poll(Timeout, TimeUnit.MILLISECONDS); 

		while(finished == true) {
			if (ack != null) {
				if(ack.getOpcode() == FINACK) {
					return;
				}

				else {
					System.err.println("sending: " + new FinPacket(expectedACK));
					socket.send(new FinPacket(expectedACK).toDatagram(srvAddress));
				}			

			}
		}

		if(ack != null) {
			if(ack.getOpcode() == ACK ) {
				if(ack.getSeqN()!= -1) {

					if(window.containsKey(ack.getSeqN())) {
						acks++;

						for (int i=0; i< PacketsSent.size();i++) {
							if(PacketsSent.get(i) == ack.getSeqN()) {
								Time += (System.currentTimeMillis() - PacketsSentTime.get(i));
								Timeout = (int)(Time/acks);

								break;

							}

						}
						handleSlide(ack.getSeqN());
						windowResize++;
						//Update window
						if(windowResize>2) {
							if(WindowSize < 26) {
								WindowSize = WindowSize * 2;
								stats.newWindowSize(WindowSize);	


							}else	
								return;
						}

					} else {
						windowResize = 1;
						System.err.println("got wrong ack");		
					}

				}
			}else {
				windowResize = 1;
				System.err.println("got unexpected packet (error)");
			}

		}else {
			//Timeout, Resend Window
			windowResize = 1;
			WindowSize = WindowSize/2;
			System.err.println("timeout...");
			Timeout=DEFAULT_TIMEOUT;
			PacketsSent= new ArrayList<Long> (WindowSize);
			PacketsSentTime = new ArrayList<Long> (WindowSize);


		}


	}







	class Stats {
		private long totalRtt = 0;
		private int timesMeasured = 0;
		private int window = WindowSize;
		private int windowSizes = WindowSize;
		private int totalPackets = 0;
		private int totalBytes = 0;
		private long startTime = 0L;;


		Stats() {
			startTime = System.currentTimeMillis();
		}

		void newPacketSent(int n) {
			totalPackets++;
			totalBytes += n;
		}

		void newTimeoutMeasure(long t) {
			timesMeasured++;
			totalRtt += t;
		}


		void newWindowSize(int n) { // to get the windowSize average
			window++;
			windowSizes += n;
		}




		void printReport() {
			// compute time spent receiving bytes
			int milliSeconds = (int) (System.currentTimeMillis() - startTime);
			float speed = (float) (totalBytes * 8.0 / milliSeconds / 1000); // M bps
			float averageRtt = (float) totalRtt / timesMeasured;
			float averageWindow = (float) (windowSizes / window);
			System.out.println("\nTransfer stats:");
			System.out.println("\nFile size:\t\t\t" + totalBytes);
			System.out.println("Packets sent:\t\t\t" + totalPackets);
			System.out.println("Average Timeout:\t\t" + Timeout);
			System.out.printf("End-to-end transfer time:\t%.3f s\n", (float) milliSeconds / 1000);
			System.out.printf("End-to-end transfer speed:\t%.3f M bps\n", speed);
			System.out.printf("Average rtt:\t\t\t%.3f ms\n", averageRtt);
			System.out.printf("Sending window size:\t\t%d packet(s)\n\n", window);
			System.out.printf("Sending average window size:\t\t%.1f packet(s)\n\n", averageWindow);



		}
	}


	public static void main(String[] args) throws Exception {
		// MyDatagramSocket.init(1, 1);
		try {
			switch (args.length) {
			case 5:
				// Ignored for S/W Client
				WindowSize = Integer.parseInt(args[4]);
			case 4:
				BlockSize = Integer.valueOf(args[3]);
				// BlockSize must be at least 1
				if ( BlockSize <= 0 ) BlockSize = DEFAULT_BLOCK_SIZE;
				// for S/W Client WindowSize is equal to BlockSize
				WindowSize = BlockSize;
			case 3:
				Timeout = Integer.valueOf(args[2]);
				// Timeout must be at least 1 ms
				if ( Timeout <= 0 ) Timeout = 1;
			case 2:
				break;
			default:
				throw new Exception("bad parameters");
			}
		} catch (Exception x) {
			System.out.printf("usage: java Ftp17Client filename server [ timeout [ blocksize [ windowsize ]]]\n");
			System.exit(0);
		}
		String filename = args[0];
		String server = args[1];
		SocketAddress srvAddr = new InetSocketAddress(server, FTP17_PORT);
		new Ftp17Client(filename, srvAddr).sendFile();
	}

	static class UploadPacket extends Ftp17Packet {
		UploadPacket(String filename) {
			putShort(UPLOAD);
			putLong(0L);
			putBytes(DUMMY_SCRATCHPAD);
			putString(filename);
		}
	}

	static class DataPacket extends Ftp17Packet {
		DataPacket(long seqN, byte[] payload, int length) {
			putShort(DATA);
			putLong( seqN );
			putBytes( DUMMY_SCRATCHPAD );
			putBytes(payload, length);
		}
	}

	static class FinPacket extends Ftp17Packet {
		FinPacket(long seqN) {
			putShort(FIN);
			putLong(seqN);
			putBytes( DUMMY_SCRATCHPAD );
		}
	}


} 
