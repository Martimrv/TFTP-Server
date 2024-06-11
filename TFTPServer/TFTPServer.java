
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * TFTP is a method to transfer files between two systems. TFTP has the ability to send
 * and receive files betweent a client and a server.
 * Write and Read requests are sent by the client to the server.
 * Server handles octet mode and treats all data as a stream of bytes.
 * Data Packet contains data bytes and block number.
 * Length of data is between 0 and 512 bytes.
 * An Error packet is sent, if an error occurs.
 */
public class TFTPServer {
	public static final int TFTPPORT = 4970;
	public static final int BUFSIZE = 516;
	public static final int DATA_SIZE = BUFSIZE - 4;
	public static final String READDIR = "./read/"; // custom address at your PC
	public static final String WRITEDIR = "./write/"; // custom address at your PC
	// public static String flag2;
	// public static final int TIMEOUT = 6000;
	// OP codes
	// OP Code == 2 bytes and int == 4 bytes
	// short == 2 bytes
	public static final int OP_RRQ = 1;
	public static final int OP_WRQ = 2;
	public static final int OP_DAT = 3;
	public static final int OP_ACK = 4;
	public static final int OP_ERR = 5;

	public static void main(String[] args) {
		if (args.length > 0) {
			System.err.printf("usage: java %s\n", TFTPServer.class.getCanonicalName());
			System.exit(1);
		}
		// Starting the server
		try {
			TFTPServer server = new TFTPServer();
			server.start();
		} catch (SocketException e) {
			e.printStackTrace();
		}
	}

	private void start() throws SocketException {
		byte[] buf = new byte[BUFSIZE];

		// Create socket
		DatagramSocket socket = new DatagramSocket(null);

		// Create local bind point
		SocketAddress localBindPoint = new InetSocketAddress(TFTPPORT);
		socket.bind(localBindPoint);

		System.out.printf("Listening at port %d for new requests\n", TFTPPORT);

		// Loop to handle client requests
		while (true) {

			final InetSocketAddress clientAddress = receiveFrom(socket, buf);

			// If clientAddress is null, an error occurred in receiveFrom()
			if (clientAddress == null)
				continue;

			final StringBuffer requestedFile = new StringBuffer();
			final int reqtype = ParseRQ(buf, requestedFile);

			new Thread() {
				public void run() {
					try {
						DatagramSocket sendSocket = new DatagramSocket(0);

						// Connect to client
						sendSocket.connect(clientAddress);

						System.out.printf("%s request for %s from %s using port %d\n",
								(reqtype == OP_RRQ) ? "Read" : "Write",
								requestedFile.toString(),
								clientAddress.getHostName(), clientAddress.getPort());

						// Read request
						if (reqtype == OP_RRQ) {
							requestedFile.insert(0, READDIR);
							HandleRQ(sendSocket, requestedFile.toString(), OP_RRQ);
						}
						// Write request
						else {
							requestedFile.insert(0, WRITEDIR);
							HandleRQ(sendSocket, requestedFile.toString(), OP_WRQ);
						}
						sendSocket.close();
					} catch (SocketException e) {
						e.printStackTrace();
					}
				}
			}.start();
		}
	}

	/**
	 * Reads the first block of data, i.e., the request for an action (read or
	 * write).
	 * 
	 * @param socket (socket to read from)
	 * @param buf    (where to store the read data)
	 * @return socketAddress (the socket address of the client)
	 */
	private InetSocketAddress receiveFrom(DatagramSocket socket, byte[] buf) {
		// Create datagram packet
		DatagramPacket receiveDataPacket = new DatagramPacket(buf, buf.length);
		try {
			// Receive packet
			socket.receive(receiveDataPacket);
		} catch (IOException e) {
			System.err.println("Error while receiving packet, " + e.getMessage());
			return null;
		}
		// Get client address and port
		InetAddress address = receiveDataPacket.getAddress();
		int port = receiveDataPacket.getPort();

		return new InetSocketAddress(address, port);
	}

	/**
	 * Parses the request in buf to retrieve the type of request and requestedFile
	 * 
	 * @param buf           (received request)
	 * @param requestedFile (name of file to read/write)
	 * @return opcode (request type: RRQ or WRQ)
	 */
	private short ParseRQ(byte[] buf, StringBuffer requestedFile) {
		// ByteBuffer - byte[] wrapped inside buffer
		ByteBuffer wrappedBuffer = ByteBuffer.wrap(buf); // Wraps a byte array into a buffer.
		// Gets the opcode from the byteArray
		short opCode = wrappedBuffer.getShort();

		// Read
		if (opCode == OP_RRQ) {
			int fileToReadIndex = 2; // File Name starts at 2
			while (buf[fileToReadIndex] != 0) { // 0 separates File Name from Mode
				requestedFile.append((char) buf[fileToReadIndex]);
				fileToReadIndex++;
			}
			// Write
		} else if (opCode == OP_WRQ) {
			int fileToWriteIndex = 2;
			while (buf[fileToWriteIndex] != 0) {
				requestedFile.append((char) buf[fileToWriteIndex]);
				fileToWriteIndex++;
			}
		} else {
			System.err.println("Error with OpCode: " + opCode);
			return -1;
		}
		return opCode;
	}

	/**
	 * Handles RRQ and WRQ requests
	 * 
	 * @param sendSocket    (socket used to send/receive packets)
	 * @param requestedFile (name of file to read/write)
	 * @param opcode        (RRQ or WRQ)
	 */
	private void HandleRQ(DatagramSocket sendSocket, String requestedFile, int opcode) {
		if (opcode == OP_RRQ) {
			File file = new File(requestedFile);
			if (!file.exists()) {
				System.err.println("Could not find file: " + requestedFile);
				send_ERR(sendSocket, 1, "File not found");
				return;
			}
			boolean success = send_DATA_receive_ACK(sendSocket, requestedFile);
			if (!success) {
				System.err.println("Error while reading" + requestedFile);
				send_ERR(sendSocket, 1, "File Not Found");
			} else {
				System.out.println("File sent!");
			}
		} else if (opcode == OP_WRQ) {
			boolean success = receive_DATA_send_ACK(sendSocket, requestedFile);
			if (!success) {
				System.err.println("Error while writing" + requestedFile);
				send_ERR(sendSocket, 4, "Error writing the file");
			} else {
				System.out.println("File received with success");
			}
		} else {
			System.err.println("Incorrect Request!");
			send_ERR(sendSocket, 5, "Request is not valid!");
			return;
		}
	}

	/**
	 * To be implemented
	 */

	/**
	 * Sends Data Packets, Receives ACK Packets
	 * 
	 * @param socketSend Datagram Socket to send and receive packets
	 * @param requestedFile name of file to send
	 * @return true, if successful
	 */
	private boolean send_DATA_receive_ACK(DatagramSocket socketSend, String requestedFile) {
		try (FileInputStream fileInput = new FileInputStream(requestedFile)) {
			int block = 1;
			byte[] buffer = new byte[DATA_SIZE];
			int byteReading;
			while ((byteReading = fileInput.read(buffer)) != -1) {
				ByteBuffer byteBuffer = ByteBuffer.allocate(byteReading + 4);
				byteBuffer.putShort((short) OP_DAT);
				byteBuffer.putShort((short) block); // block number into Pack
				byteBuffer.put(buffer, 0, byteReading);
				DatagramPacket packet = new DatagramPacket(byteBuffer.array(), byteBuffer.position(),
						socketSend.getInetAddress(), socketSend.getPort());
				int flag = 0; // keeping track
				byte[] ackBuffer = new byte[4];
				while (flag < 5) {
					socketSend.send(packet);
					DatagramPacket ack = new DatagramPacket(ackBuffer, ackBuffer.length);
					try {
						socketSend.setSoTimeout(5000);
						socketSend.receive(ack);
						break;
					} catch (SocketTimeoutException socketExc) {
						System.err.println("Timeout" + block);
						flag++;
					} catch (IOException e) {
						System.err.println("IO Error" + e.getMessage());
						send_ERR(socketSend, 1, "Error while reading");
						return false;
					}
				}
				if (flag == 5) {
					send_ERR(socketSend, 4, "Could not find file");
					return false;
				}
				ByteBuffer acknowledgeBuffer = ByteBuffer.wrap(ackBuffer);
				int acknowledgeOpCode = acknowledgeBuffer.getShort();
				int ackBlock = acknowledgeBuffer.getShort();
				if (acknowledgeOpCode != OP_ACK || ackBlock != block) {
					send_ERR(socketSend, 0, "Error with ACK packet");
					continue;
				}
				block++;
			}
			if (byteReading < BUFSIZE - 4) {
				ByteBuffer acknowledgeBuffer = ByteBuffer.allocate(4);
				acknowledgeBuffer.putShort((short) OP_ACK);
				acknowledgeBuffer.putShort((short) block);
				DatagramPacket acknowPacket = new DatagramPacket(acknowledgeBuffer.array(),
						acknowledgeBuffer.position(), socketSend.getInetAddress(), socketSend.getPort());
				socketSend.send(acknowPacket);
			}
			fileInput.close();
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}

	/**
	 * Receives Data Packets, Send Ack Packets.
	 *
	 * @param socket DatagramSocket to send and receive packets.
	 * @param fileName the name of the file.
	 * @return true, if successful.
	 */
	private boolean receive_DATA_send_ACK(DatagramSocket socket, String fileName) {
		try {
			FileOutputStream fileOutput = new FileOutputStream(fileName);
			sendACK0(socket);
			boolean checkTrue = true;
			int blockNb = 1;
			while (checkTrue) {
				byte[] bufArray = new byte[BUFSIZE];
				DatagramPacket dataPack = new DatagramPacket(bufArray, bufArray.length);
				socket.receive(dataPack);
				ByteBuffer dataByteBuffer = ByteBuffer.wrap(bufArray);
				int optionCode = dataByteBuffer.getShort();
				int blockNumber = dataByteBuffer.getShort();

				if (optionCode == OP_DAT) {
					byte[] dataToWrite = Arrays.copyOfRange(dataPack.getData(), 4, dataPack.getLength());
					// Write Data to File
					fileOutput.write(dataToWrite);
					// Send ACK 
					ByteBuffer sendResponse = ByteBuffer.allocate(4);
					sendResponse.putShort((short) OP_ACK);
					sendResponse.putShort((short) blockNb);
					DatagramPacket ackResponse = new DatagramPacket(sendResponse.array(), sendResponse.array().length);
					socket.send(ackResponse);
				} else if (optionCode == OP_RRQ) {
					System.err.println("Error with packet");
					send_ERR(socket, 0, "Incorrect Packet received");
					return false;
				} else {
					System.err.println("Incorrect OpCode");
					send_ERR(socket, 4, "");
					return false;
				}
				if (bufArray.length < 512) {
					fileOutput.close();
					checkTrue = false;
				}
				blockNb++;
			}
		} catch (IOException io) {
			io.printStackTrace();
			return false;
		}
		return true;
	}

	/**
	 * Sends error message to the client.
	 *
	 * @param socket DatagramSocket used to send the error message.
	 * @param errorCd indicates type of error.
	 * @param message error message.
	 */
	private void send_ERR(DatagramSocket socket, int errorCd, String message) {
		ByteBuffer error = ByteBuffer.allocate(message.length() + OP_ERR); // holds error msg
		error.putShort((short) OP_ERR);
		error.putShort((short) errorCd);
		error.put(message.getBytes());
		error.put((byte) 0);
		// DatagramPack with ByteBuffer as data
		DatagramPacket packet = new DatagramPacket(error.array(), error.position(), socket.getInetAddress(),
				socket.getPort());
		try {
			socket.send(packet);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Sends ACK Packet to the client.
	 *
	 * @param socket used to send ACK Packet.
	 */
	private void sendACK0(DatagramSocket socket){

        ByteBuffer sendAck = ByteBuffer.allocate(OP_ACK);
        sendAck.putShort((short)OP_ACK);
        sendAck.putShort((short)0);

        DatagramPacket send = new DatagramPacket(sendAck.array(),sendAck.array().length);
        try{
            socket.send(send);
        }catch (IOException ioe){
            ioe.printStackTrace();
        }

    }

}
