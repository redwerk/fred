package freenet.support.io;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

public class BitInputStreamTest extends TestCase {

    public void testUnalignedBytes() throws IOException {
        String bitData = "0101 00001 00001 00010 00011 00101 01000 01101 10101" // 5: 1, 1, 2, 3, 5, 8, 13, 21
                + "0011 000 001 010 011 100 101 110 111";                // 3: 0, 1, 2, 3, 4, 5, 6, 7
        bitData += "11 111 1110 00100 00";
        BigInteger bi = new BigInteger(bitData.replaceAll(" ", ""), 2);
        System.out.println("0x" + bi.toString(16) + " = 0b" + bi.toString(2));
        byte[] byteData = bi.toByteArray();
        try (BitInputStream in = new BitInputStream(new ByteArrayInputStream(byteData))) {
            int[] nmbrs = readNmbrs(in);
            System.out.println("nmbrs: " + Arrays.toString(nmbrs));
            assertTrue(Arrays.equals(new int[]{1, 1, 2, 3, 5, 8, 13, 21}, nmbrs));

            int[] nmbrs2 = readNmbrs(in);
            System.out.println("nmbrs2: " + Arrays.toString(nmbrs2));
            assertTrue(Arrays.equals(new int[]{0, 1, 2, 3, 4, 5, 6, 7}, nmbrs2));

            assertEquals(3, in.readInt(2));
            assertEquals(7, in.readInt(3));
            assertEquals(14, in.readInt(4));
            assertEquals(4, in.readInt(5));
        }
    }

    private static int[] readNmbrs(BitInputStream in) throws IOException {
        int[] nmbrs = new int[8];
        int length = in.readInt(4);
        System.out.println("length: " + length + " = 0b" + BigInteger.valueOf(length).toString(2));
        for (int i = 0; i < nmbrs.length; i++)
            nmbrs[i] = in.readInt(length);
        return nmbrs;
    }
}
