package freenet.keys;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

import junit.framework.TestCase;

import freenet.support.compress.InvalidCompressionCodecException;
import freenet.support.io.ArrayBucket;
import freenet.support.io.ArrayBucketFactory;
import freenet.support.math.MersenneTwister;

public class ClientCHKBlockTest extends TestCase {

	public void testEncodeDecodeEmptyBlock() throws CHKEncodeException, CHKVerifyException, CHKDecodeException, UnsupportedEncodingException, InvalidCompressionCodecException, IOException {
		byte[] buf = new byte[0];
		checkBlock(buf, false);
		checkBlock(buf, true);
	}
	
	public void testEncodeDecodeFullBlock() throws CHKEncodeException, CHKVerifyException, CHKDecodeException, UnsupportedEncodingException, InvalidCompressionCodecException, IOException {
		byte[] fullBlock = new byte[CHKBlock.DATA_LENGTH];
		MersenneTwister random = new MersenneTwister(42);
		for(int i=0;i<10;i++) {
			random.nextBytes(fullBlock);
			checkBlock(fullBlock, false);
			checkBlock(fullBlock, true);
		}
	}

	public void testEncodeDecodeShiftedFullBlock() throws CHKEncodeException, CHKDecodeException, IOException {
		byte[] fullBlock = new byte[CHKBlock.DATA_LENGTH];
		MersenneTwister random = new MersenneTwister(42);

		// TODO: need to agree
		int shiftLength = (int) (fullBlock.length * .3);

		for(int i=0;i<10;i++) {
			random.nextBytes(fullBlock);
			int nShiftLength = shiftLength * (i + 1);

			ClientCHKBlock encodedBlock = ClientCHKBlock.encode(new ArrayBucket(fullBlock), false, false,
					(short)-1, fullBlock.length, null, false, null, Key.ALGO_AES_CTR_256_SHA256);
			String originKey = encodedBlock.key.toString();

			encodedBlock = ClientCHKBlock.encode(new ArrayBucket(rotate(fullBlock, nShiftLength)),
					false, false,
					(short)-1, fullBlock.length, null, false, null, Key.ALGO_AES_CTR_256_SHA256);
			assertFalse(originKey.equals(encodedBlock.key.toString()));

			ArrayBucket shiftedData = (ArrayBucket)
					encodedBlock.decode(new ArrayBucketFactory(), fullBlock.length, false, true);
			assertTrue(Arrays.equals(fullBlock, rotate(shiftedData.toByteArray(), -nShiftLength)));

			System.out.println();
		}
	}

	private byte[] rotate(byte[] bytes, int shift) {
		shift = shift % bytes.length;

		if (shift == 0)
			return bytes;

		byte[] result = new byte[bytes.length];
		if (shift > 0) {
			System.arraycopy(bytes, shift, result, 0, bytes.length - shift);
			System.arraycopy(bytes, 0, result, bytes.length - shift, shift);
		}
		else {
			shift = -shift;
			System.arraycopy(bytes, bytes.length - shift, result, 0, shift);
			System.arraycopy(bytes, 0, result, shift, bytes.length - shift);
		}
		return result;
	}
	
	public void testEncodeDecodeShortInteger() throws CHKEncodeException, CHKVerifyException, CHKDecodeException, UnsupportedEncodingException, InvalidCompressionCodecException, IOException {	
		for(int i=0;i<100;i++) {
			String s = Integer.toString(i);
			checkBlock(s.getBytes("UTF-8"), false);
			checkBlock(s.getBytes("UTF-8"), true);
		}
	}
	
	public void testEncodeDecodeRandomLength() throws CHKEncodeException, CHKVerifyException, CHKDecodeException, UnsupportedEncodingException, InvalidCompressionCodecException, IOException {	
		MersenneTwister random = new MersenneTwister(42);
		for(int i=0;i<10;i++) {
			byte[] buf = new byte[random.nextInt(CHKBlock.DATA_LENGTH+1)];
			random.nextBytes(buf);
			checkBlock(buf, false);
			checkBlock(buf, true);
		}
	}
	
	public void testEncodeDecodeNearlyFullBlock() throws CHKEncodeException, CHKVerifyException, CHKDecodeException, UnsupportedEncodingException, InvalidCompressionCodecException, IOException {	
		MersenneTwister random = new MersenneTwister(68);
		for(int i=0;i<10;i++) {
			byte[] buf = new byte[CHKBlock.DATA_LENGTH - i];
			random.nextBytes(buf);
			checkBlock(buf, false);
			checkBlock(buf, true);
		}
		for(int i=0;i<10;i++) {
			byte[] buf = new byte[CHKBlock.DATA_LENGTH - (1<<i)];
			random.nextBytes(buf);
			checkBlock(buf, false);
			checkBlock(buf, true);
		}
	}
	
	private void checkBlock(byte[] data, boolean newAlgo) throws CHKEncodeException, InvalidCompressionCodecException, CHKVerifyException, CHKDecodeException, IOException {
		byte cryptoAlgorithm = newAlgo ? Key.ALGO_AES_CTR_256_SHA256 : Key.ALGO_AES_PCFB_256_SHA256;
		byte[] copyOfData = new byte[data.length];
		System.arraycopy(data, 0, copyOfData, 0, data.length);
		ClientCHKBlock encodedBlock = 
			ClientCHKBlock.encode(new ArrayBucket(data), false, false, (short)-1, data.length, null, false, null, cryptoAlgorithm);
		// Not modified in-place.
		assert(Arrays.equals(data, copyOfData));
		ClientCHK key = encodedBlock.getClientKey();
		if(newAlgo) {
			// Check with no JCA.
			ClientCHKBlock otherEncodedBlock = 
				ClientCHKBlock.encode(new ArrayBucket(data), false, false, (short)-1, data.length, null, false, null, cryptoAlgorithm, true);
			assertTrue(key.equals(otherEncodedBlock.getClientKey()));
			assertTrue(Arrays.equals(otherEncodedBlock.getBlock().data, encodedBlock.getBlock().data));
			assertTrue(Arrays.equals(otherEncodedBlock.getBlock().headers, encodedBlock.getBlock().headers));
		}
		// Verify it.
		CHKBlock block = CHKBlock.construct(encodedBlock.getBlock().data, encodedBlock.getBlock().headers, cryptoAlgorithm);
		ClientCHKBlock checkBlock = new ClientCHKBlock(block, key);
		ArrayBucket checkData = (ArrayBucket) checkBlock.decode(new ArrayBucketFactory(), data.length, false);
		assert(Arrays.equals(checkData.toByteArray(), data));
		if(newAlgo) {
			checkData = (ArrayBucket) checkBlock.decode(new ArrayBucketFactory(), data.length, false, true);
			assert(Arrays.equals(checkData.toByteArray(), data));
		}
	}

}
