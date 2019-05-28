package freenet.client.filter;

import java.io.*;
import java.nio.ByteOrder;

import freenet.support.Logger;
import freenet.support.Logger.LogLevel;
import freenet.support.io.BitInputStream;

public class TheoraPacketFilter implements CodecPacketFilter {
	static final byte[] magicNumber = new byte[] {'t', 'h', 'e', 'o', 'r', 'a'};
	enum Packet {
		IDENTIFICATION_HEADER, COMMENT_HEADER, SETUP_HEADER,
		INTRA_FRAME, INTER_FRAME
	}
	private Packet expectedPacket = Packet.IDENTIFICATION_HEADER;
	private boolean logMINOR = Logger.shouldLog(LogLevel.MINOR, this);

	public CodecPacket parse(CodecPacket packet) throws IOException {
		// Assemble the Theora packets https://www.theora.org/doc/Theora.pdf
		BitInputStream input = new BitInputStream(new ByteArrayInputStream(packet.payload));
		byte[] magicHeader = new byte[1 + magicNumber.length];
		try {
			switch(expectedPacket) {
				case IDENTIFICATION_HEADER: // must be first
					if (logMINOR)
						Logger.minor(this, "IDENTIFICATION_HEADER");

					// The header packets begin with the header type and the magic number. Validate both.
					input.readFully(magicHeader);
					checkMagicHeader(magicHeader, (byte) 0x80); // -128

					int VMAJ = input.readInt(8);
					if (VMAJ != 3) throw new UnknownContentTypeException("Header VMAJ: " + VMAJ);

					int VMIN = input.readInt(8);
					if (VMIN != 2) throw new UnknownContentTypeException("Header VMIN: " + VMIN);

					int VREV = input.readInt(8);
					if (VREV > 1) throw new UnknownContentTypeException("Header VREV: " + VREV);

					int FMBW = input.readInt(16);
					if (FMBW == 0) throw new UnknownContentTypeException("Header FMBW: " + FMBW);

					int FMBH = input.readInt(16);
					if (FMBH == 0) throw new UnknownContentTypeException("Header FMBH: " + FMBH);

					int PICW = input.readInt(24);
					if (PICW > FMBW*16) throw new UnknownContentTypeException("Header PICW: " + PICW + "; FMBW: " + FMBW);

					int PICH = input.readInt(24);
					if (PICH > FMBH*16) throw new UnknownContentTypeException("Header PICH: " + PICH + "; FMBH: " + FMBH);

					int PICX = input.readInt(8);
					if (PICX > FMBW*16-PICX) throw new UnknownContentTypeException("Header PICX: " + PICX + "; FMBW: " + FMBW + "; PICX: " + PICX);

					int PICY = input.readInt(8);
					if (PICY > FMBH*16-PICY) throw new UnknownContentTypeException("Header PICY: " + PICY + "; FMBH: " + FMBH + "; PICY: " + PICY);

					int FRN = input.readInt(32);
					if (FRN == 0) throw new UnknownContentTypeException("Header FRN: " + FRN);

					int FRD = input.readInt(32);
					if (FRD == 0) throw new UnknownContentTypeException("Header FRN: " + FRN);

					int PARN = input.readInt(24);
					int PARD = input.readInt(24);

					int CS = input.readInt(8);
					if (!(CS == 0 || CS == 1 || CS == 2)) throw new UnknownContentTypeException("Header CS: " + CS);

					int NOMBR = input.readInt(24);
					int QUAL = input.readInt(6);
					int KFGSHIFT = input.readInt(5);

					int PF = input.readInt(2);
					if (PF == 1) throw new UnknownContentTypeException("Header PF: " + PF);

					int Res = input.readInt(3);
					if (Res != 0) throw new UnknownContentTypeException("Header Res: " + Res);

					expectedPacket = Packet.COMMENT_HEADER;
					break;

				case COMMENT_HEADER: // must be second
					if (logMINOR)
						Logger.minor(this, "COMMENT_HEADER");

					input.readFully(magicHeader);
					checkMagicHeader(magicHeader, (byte) 0x81); // -127

					int vendorLength = input.readInt(32, ByteOrder.LITTLE_ENDIAN);
					byte[] vendor = new byte[vendorLength];
					input.readFully(vendor);
					if (logMINOR)
						Logger.minor(this, "Vendor string is: " + new String(vendor));
					int numberOfComments = input.readInt(32, ByteOrder.LITTLE_ENDIAN);
					for (long i = 0; i < numberOfComments; i++) {
						int commentLength = input.readInt(32, ByteOrder.LITTLE_ENDIAN);
						byte[] comment = new byte[commentLength];
						input.readFully(comment);
						if (logMINOR)
							Logger.minor(this, "Comment string is: " + new String(comment));
					}
					if (logMINOR)
						Logger.minor(this, "COMMENT_HEADER contains " + input.available() + " redundant bytes");

					// skip vendor string and comments
					try (ByteArrayOutputStream data = new ByteArrayOutputStream();
						 DataOutputStream output = new DataOutputStream(data)) {
						output.write(magicHeader);
						output.writeLong(0);
						packet = new CodecPacket(data.toByteArray());
					}

					expectedPacket = Packet.SETUP_HEADER;
					break;

				case SETUP_HEADER: // must be third
					if (logMINOR)
						Logger.minor(this, "SETUP_HEADER");

					input.readFully(magicHeader);
					checkMagicHeader(magicHeader, (byte) 0x82); // -126

					// TODO: end of page 50 - missing algorithm
					if (logMINOR)
						Logger.minor(this, "SETUP_HEADER: available " + input.available() + " bytes");

					int NBITS = input.readInt(4) + 1;
					if (logMINOR)
						Logger.minor(this, "    NBITS: " + NBITS);
					int[] ACSCALE = new int[64];
					for (int i = 0; i < ACSCALE.length; i++)
						ACSCALE[i] = input.readInt(NBITS);

					NBITS = input.readInt(4) + 1;
					if (logMINOR)
						Logger.minor(this, "    NBITS: " + NBITS);
					int[] DCSCALE = new int[64];
					for (int i = 0; i < DCSCALE.length; i++)
						DCSCALE[i] = input.readInt(NBITS);

					int NBMS = input.readInt(9) + 1;
					if (logMINOR)
						Logger.minor(this, "    NBITS: " + NBMS);
					if (NBMS > 384)
						throw new UnknownContentTypeException("SETUP HEADER - NBMS: " + NBMS + "(MUST be no greater than 384)");

					// TODO: page 53 point 6 - eof exception happens, probably too high NBMS
//					int[][] BMS = new int[NBMS][64];
//					for (int i = 0; i < BMS.length; i++)
//						for (int j = 0; j < BMS[i].length; j++)
//							BMS[i][j] = input.readInt(8);
//
//					for (int qti = 0; qti <= 1; qti++) {
//						for (int pli = 0; pli <= 2; pli++) {
//							int NEWQR = 1;
//							if (qti > 0 || pli > 0)
//								NEWQR = input.readBit();
//
//							int[][] NQRS = new int[2][3];
//							int[][][] QRSIZES = new int[2][3][63];
//							int[][][] QRBMIS = new int[2][3][64];
//							if (NEWQR == 0) {
//								int qtj, plj;
//								int RPQR = 0;
//								if (qti > 0)
//									RPQR = input.readBit();
//
//								if (RPQR == 1) {
//									qtj = qti - 1;
//									plj = pli;
//								}
//								else {
////									qtj = (3 * qti + pli - 1) // 3; // TODO: See conventions - Integer division
//									plj = (pli + 2) % 3;
//								}
//
//								NQRS[qti][pli] = NQRS[qtj][plj];
//								QRSIZES[qti ][pli ] = QRSIZES[qtj][plj];
//								QRBMIS[qti ][pli ] = QRBMIS[qtj][plj];
//							}
//							else {
//								int qri = 0;
//								int qi = 0;
//
//								// TODO: page 54 point C - ilog(NBMS − 1) See conventions
////								QRBMIS[qti][pli][qri] =
//							}
//						}
//					}

					expectedPacket = Packet.INTRA_FRAME;
					break;

				case INTRA_FRAME: // first in Frame
					if (logMINOR)
						Logger.minor(this, "INTRA_FRAME");

					try {
						int firstBit = input.readBit();

						if (firstBit != 0)
							throw new DataFilterException("First bit is not zero - this is not a data packet");
					}
					catch (EOFException e) {
						throw new DataFilterException("Empty package");
					}

					int FTYPE = input.readBit();
					if (FTYPE != 0)
						throw new DataFilterException("First frame type must be Intra frame");

					int[] QIS = new int[3];
					int NQIS = 0;
					do {
						QIS[NQIS++] = input.readInt(6);
					} while (input.readBit() == 1 && NQIS < 3); // MOREQIS

					int reservedBits = input.readInt(3);
					if (reservedBits != 0)
						throw new DataFilterException("This frame is not decodable according to this specification");

					// TODO: page 64 required NBITS

					expectedPacket = Packet.INTER_FRAME;
					break;

				case INTER_FRAME:
					if (logMINOR)
						Logger.minor(this, "INTER_FRAME");

					try {
						int firstBit = input.readBit();

						if (firstBit != 0) // TODO: some other but need it
							break;
					}
					catch (EOFException e) {
						throw new DataFilterException("Empty package");
					}

					FTYPE = input.readBit();
//					if (FTYPE == 0) // TODO: Perhaps this may be INTRA_FRAME
			}
		} catch(IOException e) {
			if (logMINOR) Logger.minor(this, "In Theora parser caught " + e, e);
			throw e;
		}

		return packet;
	}

	private void checkMagicHeader(byte[] typeAndMagicHeader, byte expectedType) throws IOException {
		if (logMINOR) Logger.minor(this, "Header type: " + typeAndMagicHeader[0]);

		if (typeAndMagicHeader[0] != expectedType)
			throw new UnknownContentTypeException("Header type: " + typeAndMagicHeader[0] + ", expected: " + expectedType);

		for (int i=0; i < magicNumber.length; i++) {
			if (typeAndMagicHeader[i+1] != magicNumber[i])
				throw new UnknownContentTypeException("Packet header magicNumber[" + i + "]: " + typeAndMagicHeader[i+1]);
		}
	}
}
