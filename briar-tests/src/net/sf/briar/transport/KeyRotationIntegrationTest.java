package net.sf.briar.transport;

import static net.sf.briar.api.transport.TransportConstants.MAX_CLOCK_DIFFERENCE;
import static net.sf.briar.api.transport.TransportConstants.TAG_LENGTH;
import static org.junit.Assert.assertArrayEquals;

import java.util.Arrays;
import java.util.Collections;

import javax.crypto.Cipher;
import javax.crypto.NullCipher;

import net.sf.briar.BriarTestCase;
import net.sf.briar.TestUtils;
import net.sf.briar.api.ContactId;
import net.sf.briar.api.TransportId;
import net.sf.briar.api.clock.Clock;
import net.sf.briar.api.clock.Timer;
import net.sf.briar.api.crypto.CryptoComponent;
import net.sf.briar.api.crypto.ErasableKey;
import net.sf.briar.api.db.DatabaseComponent;
import net.sf.briar.api.db.event.DatabaseListener;
import net.sf.briar.api.transport.ConnectionContext;
import net.sf.briar.api.transport.ConnectionRecogniser;
import net.sf.briar.api.transport.Endpoint;
import net.sf.briar.api.transport.TemporarySecret;
import net.sf.briar.util.ByteUtils;

import org.hamcrest.Description;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.junit.Test;

public class KeyRotationIntegrationTest extends BriarTestCase {

	private static final long EPOCH = 1000L * 1000L * 1000L * 1000L;
	private static final long MAX_LATENCY = 2 * 60 * 1000; // 2 minutes
	private static final long ROTATION_PERIOD_LENGTH =
			MAX_LATENCY + MAX_CLOCK_DIFFERENCE;

	private final ContactId contactId;
	private final TransportId transportId;
	private final byte[] secret0, secret1, secret2, secret3, secret4;
	private final byte[] key0, key1, key2, key3, key4;
	private final byte[] initialSecret;
	private final Cipher cipher;

	public KeyRotationIntegrationTest() {
		contactId = new ContactId(234);
		transportId = new TransportId(TestUtils.getRandomId());
		secret0 = new byte[32];
		secret1 = new byte[32];
		secret2 = new byte[32];
		secret3 = new byte[32];
		secret4 = new byte[32];
		for(int i = 0; i < secret0.length; i++) secret0[i] = 1;
		for(int i = 0; i < secret1.length; i++) secret1[i] = 2;
		for(int i = 0; i < secret2.length; i++) secret2[i] = 3;
		for(int i = 0; i < secret3.length; i++) secret3[i] = 4;
		for(int i = 0; i < secret4.length; i++) secret4[i] = 5;
		key0 = new byte[32];
		key1 = new byte[32];
		key2 = new byte[32];
		key3 = new byte[32];
		key4 = new byte[32];
		for(int i = 0; i < key0.length; i++) key0[i] = 1;
		for(int i = 0; i < key1.length; i++) key1[i] = 2;
		for(int i = 0; i < key2.length; i++) key2[i] = 3;
		for(int i = 0; i < key3.length; i++) key3[i] = 4;
		for(int i = 0; i < key4.length; i++) key4[i] = 5;
		initialSecret = new byte[32];
		for(int i = 0; i < initialSecret.length; i++) initialSecret[i] = 123;
		cipher = new NullCipher();
	}

	@Test
	public void testStartAndStop() throws Exception {
		Mockery context = new Mockery();
		final CryptoComponent crypto = context.mock(CryptoComponent.class);
		final DatabaseComponent db = context.mock(DatabaseComponent.class);
		final Clock clock = context.mock(Clock.class);
		final Timer timer = context.mock(Timer.class);

		final ConnectionRecogniser connectionRecogniser =
				new ConnectionRecogniserImpl(crypto, db);
		final KeyManagerImpl keyManager = new KeyManagerImpl(crypto, db,
				connectionRecogniser, clock, timer);

		context.checking(new Expectations() {{
			// start()
			oneOf(db).addListener(with(any(DatabaseListener.class)));
			oneOf(db).getSecrets();
			will(returnValue(Collections.emptyList()));
			oneOf(db).getTransportLatencies();
			will(returnValue(Collections.emptyMap()));
			oneOf(clock).currentTimeMillis();
			will(returnValue(EPOCH));
			oneOf(timer).scheduleAtFixedRate(with(keyManager),
					with(any(long.class)), with(any(long.class)));
			// stop()
			oneOf(db).removeListener(with(any(DatabaseListener.class)));
			oneOf(timer).cancel();
		}});

		assertTrue(keyManager.start());
		keyManager.stop();

		context.assertIsSatisfied();
	}

	@Test
	public void testEndpointAdded() throws Exception {
		Mockery context = new Mockery();
		final CryptoComponent crypto = context.mock(CryptoComponent.class);
		final DatabaseComponent db = context.mock(DatabaseComponent.class);
		final Clock clock = context.mock(Clock.class);
		final Timer timer = context.mock(Timer.class);
		final ErasableKey k0 = context.mock(ErasableKey.class, "k0");
		final ErasableKey k1 = context.mock(ErasableKey.class, "k1");
		final ErasableKey k2 = context.mock(ErasableKey.class, "k2");

		final ConnectionRecogniser connectionRecogniser =
				new ConnectionRecogniserImpl(crypto, db);
		final KeyManagerImpl keyManager = new KeyManagerImpl(crypto, db,
				connectionRecogniser, clock, timer);

		// The secrets for periods 0 - 2 should be derived
		Endpoint ep = new Endpoint(contactId, transportId, EPOCH, true);
		final TemporarySecret s0 = new TemporarySecret(ep, 0, secret0.clone());
		final TemporarySecret s1 = new TemporarySecret(ep, 1, secret1.clone());
		final TemporarySecret s2 = new TemporarySecret(ep, 2, secret2.clone());

		context.checking(new Expectations() {{
			// start()
			oneOf(db).addListener(with(any(DatabaseListener.class)));
			oneOf(db).getSecrets();
			will(returnValue(Collections.emptyList()));
			oneOf(db).getTransportLatencies();
			will(returnValue(Collections.singletonMap(transportId,
					MAX_LATENCY)));
			oneOf(clock).currentTimeMillis();
			will(returnValue(EPOCH));
			oneOf(timer).scheduleAtFixedRate(with(keyManager),
					with(any(long.class)), with(any(long.class)));
			// endpointAdded() during rotation period 1
			oneOf(clock).currentTimeMillis();
			will(returnValue(EPOCH));
			oneOf(crypto).deriveNextSecret(initialSecret, 0);
			will(returnValue(secret0.clone()));
			oneOf(crypto).deriveNextSecret(secret0, 1);
			will(returnValue(secret1.clone()));
			oneOf(crypto).deriveNextSecret(secret1, 2);
			will(returnValue(secret2.clone()));
			oneOf(db).addSecrets(Arrays.asList(s0, s1, s2));
			// The recogniser should derive the tags for period 0
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret0, false);
			will(returnValue(k0));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k0), with((long) i));
				will(new EncodeTagAction());
				oneOf(k0).getEncoded();
				will(returnValue(key0));
			}
			oneOf(k0).erase();
			// The recogniser should derive the tags for period 1
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret1, false);
			will(returnValue(k1));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k1), with((long) i));
				will(new EncodeTagAction());
				oneOf(k1).getEncoded();
				will(returnValue(key1));
			}
			oneOf(k1).erase();
			// The recogniser should derive the tags for period 2
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret2, false);
			will(returnValue(k2));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k2), with((long) i));
				will(new EncodeTagAction());
				oneOf(k2).getEncoded();
				will(returnValue(key2));
			}
			oneOf(k2).erase();
			// stop()
			// The recogniser should derive the tags for period 0
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret0, false);
			will(returnValue(k0));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k0), with((long) i));
				will(new EncodeTagAction());
				oneOf(k0).getEncoded();
				will(returnValue(key0));
			}
			oneOf(k0).erase();
			// The recogniser should derive the tags for period 1
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret1, false);
			will(returnValue(k1));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k1), with((long) i));
				will(new EncodeTagAction());
				oneOf(k1).getEncoded();
				will(returnValue(key1));
			}
			oneOf(k1).erase();
			// The recogniser should derive the tags for period 2
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret2, false);
			will(returnValue(k2));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k2), with((long) i));
				will(new EncodeTagAction());
				oneOf(k2).getEncoded();
				will(returnValue(key2));
			}
			oneOf(k2).erase();
			// Remove the listener and stop the timer
			oneOf(db).removeListener(with(any(DatabaseListener.class)));
			oneOf(timer).cancel();
		}});

		assertTrue(keyManager.start());
		keyManager.endpointAdded(ep, MAX_LATENCY, initialSecret.clone());
		keyManager.stop();

		context.assertIsSatisfied();
	}

	@Test
	public void testEndpointAddedAndGetConnectionContext() throws Exception {
		Mockery context = new Mockery();
		final CryptoComponent crypto = context.mock(CryptoComponent.class);
		final DatabaseComponent db = context.mock(DatabaseComponent.class);
		final Clock clock = context.mock(Clock.class);
		final Timer timer = context.mock(Timer.class);
		final ErasableKey k0 = context.mock(ErasableKey.class, "k0");
		final ErasableKey k1 = context.mock(ErasableKey.class, "k1");
		final ErasableKey k2 = context.mock(ErasableKey.class, "k2");

		final ConnectionRecogniser connectionRecogniser =
				new ConnectionRecogniserImpl(crypto, db);
		final KeyManagerImpl keyManager = new KeyManagerImpl(crypto, db,
				connectionRecogniser, clock, timer);

		// The secrets for periods 0 - 2 should be derived
		Endpoint ep = new Endpoint(contactId, transportId, EPOCH, true);
		final TemporarySecret s0 = new TemporarySecret(ep, 0, secret0.clone());
		final TemporarySecret s1 = new TemporarySecret(ep, 1, secret1.clone());
		final TemporarySecret s2 = new TemporarySecret(ep, 2, secret2.clone());

		context.checking(new Expectations() {{
			// start()
			oneOf(db).addListener(with(any(DatabaseListener.class)));
			oneOf(db).getSecrets();
			will(returnValue(Collections.emptyList()));
			oneOf(db).getTransportLatencies();
			will(returnValue(Collections.singletonMap(transportId,
					MAX_LATENCY)));
			oneOf(clock).currentTimeMillis();
			will(returnValue(EPOCH));
			oneOf(timer).scheduleAtFixedRate(with(keyManager),
					with(any(long.class)), with(any(long.class)));
			// endpointAdded() during rotation period 1
			oneOf(clock).currentTimeMillis();
			will(returnValue(EPOCH));
			oneOf(crypto).deriveNextSecret(initialSecret, 0);
			will(returnValue(secret0.clone()));
			oneOf(crypto).deriveNextSecret(secret0, 1);
			will(returnValue(secret1.clone()));
			oneOf(crypto).deriveNextSecret(secret1, 2);
			will(returnValue(secret2.clone()));
			oneOf(db).addSecrets(Arrays.asList(s0, s1, s2));
			// The recogniser should derive the tags for period 0
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret0, false);
			will(returnValue(k0));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k0), with((long) i));
				will(new EncodeTagAction());
				oneOf(k0).getEncoded();
				will(returnValue(key0));
			}
			oneOf(k0).erase();
			// The recogniser should derive the tags for period 1
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret1, false);
			will(returnValue(k1));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k1), with((long) i));
				will(new EncodeTagAction());
				oneOf(k1).getEncoded();
				will(returnValue(key1));
			}
			oneOf(k1).erase();
			// The recogniser should derive the tags for period 2
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret2, false);
			will(returnValue(k2));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k2), with((long) i));
				will(new EncodeTagAction());
				oneOf(k2).getEncoded();
				will(returnValue(key2));
			}
			oneOf(k2).erase();
			// getConnectionContext()
			oneOf(db).incrementConnectionCounter(contactId, transportId, 1);
			will(returnValue(0L));
			// stop()
			// The recogniser should derive the tags for period 0
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret0, false);
			will(returnValue(k0));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k0), with((long) i));
				will(new EncodeTagAction());
				oneOf(k0).getEncoded();
				will(returnValue(key0));
			}
			oneOf(k0).erase();
			// The recogniser should derive the tags for period 1
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret1, false);
			will(returnValue(k1));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k1), with((long) i));
				will(new EncodeTagAction());
				oneOf(k1).getEncoded();
				will(returnValue(key1));
			}
			oneOf(k1).erase();
			// The recogniser should derive the tags for period 2
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret2, false);
			will(returnValue(k2));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k2), with((long) i));
				will(new EncodeTagAction());
				oneOf(k2).getEncoded();
				will(returnValue(key2));
			}
			oneOf(k2).erase();
			// Remove the listener and stop the timer
			oneOf(db).removeListener(with(any(DatabaseListener.class)));
			oneOf(timer).cancel();
		}});

		assertTrue(keyManager.start());
		keyManager.endpointAdded(ep, MAX_LATENCY, initialSecret.clone());
		ConnectionContext ctx =
				keyManager.getConnectionContext(contactId, transportId);
		assertNotNull(ctx);
		assertEquals(contactId, ctx.getContactId());
		assertEquals(transportId, ctx.getTransportId());
		assertArrayEquals(secret1, ctx.getSecret());
		assertEquals(0, ctx.getConnectionNumber());
		assertEquals(true, ctx.getAlice());
		keyManager.stop();

		context.assertIsSatisfied();
	}

	@Test
	public void testEndpointAddedAndAcceptConnection() throws Exception {
		Mockery context = new Mockery();
		final CryptoComponent crypto = context.mock(CryptoComponent.class);
		final DatabaseComponent db = context.mock(DatabaseComponent.class);
		final Clock clock = context.mock(Clock.class);
		final Timer timer = context.mock(Timer.class);
		final ErasableKey k0 = context.mock(ErasableKey.class, "k0");
		final ErasableKey k1 = context.mock(ErasableKey.class, "k1");
		final ErasableKey k2 = context.mock(ErasableKey.class, "k2");

		final ConnectionRecogniser connectionRecogniser =
				new ConnectionRecogniserImpl(crypto, db);
		final KeyManagerImpl keyManager = new KeyManagerImpl(crypto, db,
				connectionRecogniser, clock, timer);

		// The secrets for periods 0 - 2 should be derived
		Endpoint ep = new Endpoint(contactId, transportId, EPOCH, true);
		final TemporarySecret s0 = new TemporarySecret(ep, 0, secret0.clone());
		final TemporarySecret s1 = new TemporarySecret(ep, 1, secret1.clone());
		final TemporarySecret s2 = new TemporarySecret(ep, 2, secret2.clone());

		context.checking(new Expectations() {{
			// start()
			oneOf(db).addListener(with(any(DatabaseListener.class)));
			oneOf(db).getSecrets();
			will(returnValue(Collections.emptyList()));
			oneOf(db).getTransportLatencies();
			will(returnValue(Collections.singletonMap(transportId,
					MAX_LATENCY)));
			oneOf(clock).currentTimeMillis();
			will(returnValue(EPOCH));
			oneOf(timer).scheduleAtFixedRate(with(keyManager),
					with(any(long.class)), with(any(long.class)));
			// endpointAdded() during rotation period 1
			oneOf(clock).currentTimeMillis();
			will(returnValue(EPOCH));
			oneOf(crypto).deriveNextSecret(initialSecret, 0);
			will(returnValue(secret0.clone()));
			oneOf(crypto).deriveNextSecret(secret0, 1);
			will(returnValue(secret1.clone()));
			oneOf(crypto).deriveNextSecret(secret1, 2);
			will(returnValue(secret2.clone()));
			oneOf(db).addSecrets(Arrays.asList(s0, s1, s2));
			// The recogniser should derive the tags for period 0
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret0, false);
			will(returnValue(k0));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k0), with((long) i));
				will(new EncodeTagAction());
				oneOf(k0).getEncoded();
				will(returnValue(key0));
			}
			oneOf(k0).erase();
			// The recogniser should derive the tags for period 1
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret1, false);
			will(returnValue(k1));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k1), with((long) i));
				will(new EncodeTagAction());
				oneOf(k1).getEncoded();
				will(returnValue(key1));
			}
			oneOf(k1).erase();
			// The recogniser should derive the tags for period 2
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret2, false);
			will(returnValue(k2));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k2), with((long) i));
				will(new EncodeTagAction());
				oneOf(k2).getEncoded();
				will(returnValue(key2));
			}
			oneOf(k2).erase();
			// acceptConnection()
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret2, false);
			will(returnValue(k2));
			oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
					with(k2), with(16L));
			will(new EncodeTagAction());
			oneOf(k2).getEncoded();
			will(returnValue(key2));
			oneOf(db).setConnectionWindow(contactId, transportId, 2, 1,
					new byte[] {0, 1, 0, 0});
			oneOf(k2).erase();
			// stop()
			// The recogniser should derive the tags for period 0
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret0, false);
			will(returnValue(k0));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k0), with((long) i));
				will(new EncodeTagAction());
				oneOf(k0).getEncoded();
				will(returnValue(key0));
			}
			oneOf(k0).erase();
			// The recogniser should derive the tags for period 1
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret1, false);
			will(returnValue(k1));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k1), with((long) i));
				will(new EncodeTagAction());
				oneOf(k1).getEncoded();
				will(returnValue(key1));
			}
			oneOf(k1).erase();
			// The recogniser should derive the updated tags for period 2
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret2, false);
			will(returnValue(k2));
			for(int i = 1; i < 17; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k2), with((long) i));
				will(new EncodeTagAction());
				oneOf(k2).getEncoded();
				will(returnValue(key2));
			}
			oneOf(k2).erase();
			// Remove the listener and stop the timer
			oneOf(db).removeListener(with(any(DatabaseListener.class)));
			oneOf(timer).cancel();
		}});

		assertTrue(keyManager.start());
		keyManager.endpointAdded(ep, MAX_LATENCY, initialSecret.clone());
		// Recognise the tag for connection 0 in period 2
		byte[] tag = new byte[TAG_LENGTH];
		encodeTag(tag, key2, 0);
		ConnectionContext ctx =
				connectionRecogniser.acceptConnection(transportId, tag);
		assertNotNull(ctx);
		assertEquals(contactId, ctx.getContactId());
		assertEquals(transportId, ctx.getTransportId());
		assertArrayEquals(secret2, ctx.getSecret());
		assertEquals(0, ctx.getConnectionNumber());
		assertEquals(true, ctx.getAlice());
		keyManager.stop();

		context.assertIsSatisfied();
	}

	@Test
	public void testLoadSecretsAtEpoch() throws Exception {
		Mockery context = new Mockery();
		final CryptoComponent crypto = context.mock(CryptoComponent.class);
		final DatabaseComponent db = context.mock(DatabaseComponent.class);
		final Clock clock = context.mock(Clock.class);
		final Timer timer = context.mock(Timer.class);
		final ErasableKey k0 = context.mock(ErasableKey.class, "k0");
		final ErasableKey k1 = context.mock(ErasableKey.class, "k1");
		final ErasableKey k2 = context.mock(ErasableKey.class, "k2");

		final ConnectionRecogniser connectionRecogniser =
				new ConnectionRecogniserImpl(crypto, db);
		final KeyManagerImpl keyManager = new KeyManagerImpl(crypto, db,
				connectionRecogniser, clock, timer);

		// The DB contains the secrets for periods 0 - 2
		Endpoint ep = new Endpoint(contactId, transportId, EPOCH, true);
		final TemporarySecret s0 = new TemporarySecret(ep, 0, secret0.clone());
		final TemporarySecret s1 = new TemporarySecret(ep, 1, secret1.clone());
		final TemporarySecret s2 = new TemporarySecret(ep, 2, secret2.clone());

		context.checking(new Expectations() {{
			// start()
			oneOf(db).addListener(with(any(DatabaseListener.class)));
			oneOf(db).getSecrets();
			will(returnValue(Arrays.asList(s0, s1, s2)));
			oneOf(db).getTransportLatencies();
			will(returnValue(Collections.singletonMap(transportId,
					MAX_LATENCY)));
			// The current time is the epoch, the start of period 1
			oneOf(clock).currentTimeMillis();
			will(returnValue(EPOCH));
			// The recogniser should derive the tags for period 0
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret0, false);
			will(returnValue(k0));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k0), with((long) i));
				will(new EncodeTagAction());
				oneOf(k0).getEncoded();
				will(returnValue(key0));
			}
			oneOf(k0).erase();
			// The recogniser should derive the tags for period 1
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret1, false);
			will(returnValue(k1));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k1), with((long) i));
				will(new EncodeTagAction());
				oneOf(k1).getEncoded();
				will(returnValue(key1));
			}
			oneOf(k1).erase();
			// The recogniser should derive the tags for period 2
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret2, false);
			will(returnValue(k2));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k2), with((long) i));
				will(new EncodeTagAction());
				oneOf(k2).getEncoded();
				will(returnValue(key2));
			}
			oneOf(k2).erase();
			// Start the timer
			oneOf(timer).scheduleAtFixedRate(with(keyManager),
					with(any(long.class)), with(any(long.class)));
			// stop()
			// The recogniser should remove the tags for period 0
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret0, false);
			will(returnValue(k0));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k0), with((long) i));
				will(new EncodeTagAction());
				oneOf(k0).getEncoded();
				will(returnValue(key0));
			}
			oneOf(k0).erase();
			// The recogniser should derive the tags for period 1
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret1, false);
			will(returnValue(k1));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k1), with((long) i));
				will(new EncodeTagAction());
				oneOf(k1).getEncoded();
				will(returnValue(key1));
			}
			oneOf(k1).erase();
			// The recogniser should derive the tags for period 2
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret2, false);
			will(returnValue(k2));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k2), with((long) i));
				will(new EncodeTagAction());
				oneOf(k2).getEncoded();
				will(returnValue(key2));
			}
			oneOf(k2).erase();
			// Remove the listener and stop the timer
			oneOf(db).removeListener(with(any(DatabaseListener.class)));
			oneOf(timer).cancel();
		}});

		assertTrue(keyManager.start());
		keyManager.stop();

		context.assertIsSatisfied();
	}

	@Test
	public void testLoadSecretsAtStartOfPeriod2() throws Exception {
		Mockery context = new Mockery();
		final CryptoComponent crypto = context.mock(CryptoComponent.class);
		final DatabaseComponent db = context.mock(DatabaseComponent.class);
		final Clock clock = context.mock(Clock.class);
		final Timer timer = context.mock(Timer.class);
		final ErasableKey k1 = context.mock(ErasableKey.class, "k1");
		final ErasableKey k2 = context.mock(ErasableKey.class, "k2");
		final ErasableKey k3 = context.mock(ErasableKey.class, "k3");

		final ConnectionRecogniser connectionRecogniser =
				new ConnectionRecogniserImpl(crypto, db);
		final KeyManagerImpl keyManager = new KeyManagerImpl(crypto, db,
				connectionRecogniser, clock, timer);

		// The DB contains the secrets for periods 0 - 2
		Endpoint ep = new Endpoint(contactId, transportId, EPOCH, true);
		final TemporarySecret s0 = new TemporarySecret(ep, 0, secret0.clone());
		final TemporarySecret s1 = new TemporarySecret(ep, 1, secret1.clone());
		final TemporarySecret s2 = new TemporarySecret(ep, 2, secret2.clone());
		// The secret for period 3 should be derived and stored
		final TemporarySecret s3 = new TemporarySecret(ep, 3, secret3.clone());

		context.checking(new Expectations() {{
			// start()
			oneOf(db).addListener(with(any(DatabaseListener.class)));
			oneOf(db).getSecrets();
			will(returnValue(Arrays.asList(s0, s1, s2)));
			oneOf(db).getTransportLatencies();
			will(returnValue(Collections.singletonMap(transportId,
					MAX_LATENCY)));
			// The current time is the start of period 2
			oneOf(clock).currentTimeMillis();
			will(returnValue(EPOCH + ROTATION_PERIOD_LENGTH));
			// The secret for period 3 should be derived and stored
			oneOf(crypto).deriveNextSecret(secret0, 1);
			will(returnValue(secret1.clone()));
			oneOf(crypto).deriveNextSecret(secret1, 2);
			will(returnValue(secret2.clone()));
			oneOf(crypto).deriveNextSecret(secret2, 3);
			will(returnValue(secret3.clone()));
			oneOf(db).addSecrets(Arrays.asList(s3));
			// The recogniser should derive the tags for period 1
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret1, false);
			will(returnValue(k1));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k1), with((long) i));
				will(new EncodeTagAction());
				oneOf(k1).getEncoded();
				will(returnValue(key1));
			}
			oneOf(k1).erase();
			// The recogniser should derive the tags for period 2
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret2, false);
			will(returnValue(k2));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k2), with((long) i));
				will(new EncodeTagAction());
				oneOf(k2).getEncoded();
				will(returnValue(key2));
			}
			oneOf(k2).erase();
			// The recogniser should derive the tags for period 3
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret3, false);
			will(returnValue(k3));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k3), with((long) i));
				will(new EncodeTagAction());
				oneOf(k3).getEncoded();
				will(returnValue(key3));
			}
			oneOf(k3).erase();
			// Start the timer
			oneOf(timer).scheduleAtFixedRate(with(keyManager),
					with(any(long.class)), with(any(long.class)));
			// stop()
			// The recogniser should derive the tags for period 1
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret1, false);
			will(returnValue(k1));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k1), with((long) i));
				will(new EncodeTagAction());
				oneOf(k1).getEncoded();
				will(returnValue(key1));
			}
			oneOf(k1).erase();
			// The recogniser should derive the tags for period 2
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret2, false);
			will(returnValue(k2));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k2), with((long) i));
				will(new EncodeTagAction());
				oneOf(k2).getEncoded();
				will(returnValue(key2));
			}
			oneOf(k2).erase();
			// The recogniser should remove the tags for period 3
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret3, false);
			will(returnValue(k3));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k3), with((long) i));
				will(new EncodeTagAction());
				oneOf(k3).getEncoded();
				will(returnValue(key3));
			}
			oneOf(k3).erase();
			// Remove the listener and stop the timer
			oneOf(db).removeListener(with(any(DatabaseListener.class)));
			oneOf(timer).cancel();
		}});

		assertTrue(keyManager.start());
		keyManager.stop();

		context.assertIsSatisfied();
	}

	@Test
	public void testLoadSecretsAtEndOfPeriod3() throws Exception {
		Mockery context = new Mockery();
		final CryptoComponent crypto = context.mock(CryptoComponent.class);
		final DatabaseComponent db = context.mock(DatabaseComponent.class);
		final Clock clock = context.mock(Clock.class);
		final Timer timer = context.mock(Timer.class);
		final ErasableKey k2 = context.mock(ErasableKey.class, "k2");
		final ErasableKey k3 = context.mock(ErasableKey.class, "k3");
		final ErasableKey k4 = context.mock(ErasableKey.class, "k4");

		final ConnectionRecogniser connectionRecogniser =
				new ConnectionRecogniserImpl(crypto, db);
		final KeyManagerImpl keyManager = new KeyManagerImpl(crypto, db,
				connectionRecogniser, clock, timer);

		// The DB contains the secrets for periods 0 - 2
		Endpoint ep = new Endpoint(contactId, transportId, EPOCH, true);
		final TemporarySecret s0 = new TemporarySecret(ep, 0, secret0.clone());
		final TemporarySecret s1 = new TemporarySecret(ep, 1, secret1.clone());
		final TemporarySecret s2 = new TemporarySecret(ep, 2, secret2.clone());
		// The secrets for periods 3 and 4 should be derived and stored
		final TemporarySecret s3 = new TemporarySecret(ep, 3, secret3.clone());
		final TemporarySecret s4 = new TemporarySecret(ep, 4, secret4.clone());

		context.checking(new Expectations() {{
			// start()
			oneOf(db).addListener(with(any(DatabaseListener.class)));
			oneOf(db).getSecrets();
			will(returnValue(Arrays.asList(s0, s1, s2)));
			oneOf(db).getTransportLatencies();
			will(returnValue(Collections.singletonMap(transportId,
					MAX_LATENCY)));
			// The current time is the end of period 3
			oneOf(clock).currentTimeMillis();
			will(returnValue(EPOCH + 3 * ROTATION_PERIOD_LENGTH - 1));
			// The secrets for periods 3 and 4 should be derived from secret 1
			oneOf(crypto).deriveNextSecret(secret1, 2);
			will(returnValue(secret2.clone()));
			oneOf(crypto).deriveNextSecret(secret2, 3);
			will(returnValue(secret3.clone()));
			oneOf(crypto).deriveNextSecret(secret3, 4);
			will(returnValue(secret4.clone()));
			// The new secrets should be stored
			oneOf(db).addSecrets(Arrays.asList(s3, s4));
			// The recogniser should derive the tags for period 2
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret2, false);
			will(returnValue(k2));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k2), with((long) i));
				will(new EncodeTagAction());
				oneOf(k2).getEncoded();
				will(returnValue(key2));
			}
			oneOf(k2).erase();
			// The recogniser should derive the tags for period 3
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret3, false);
			will(returnValue(k3));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k3), with((long) i));
				will(new EncodeTagAction());
				oneOf(k3).getEncoded();
				will(returnValue(key3));
			}
			oneOf(k3).erase();
			// The recogniser should derive the tags for period 4
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret4, false);
			will(returnValue(k4));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k4), with((long) i));
				will(new EncodeTagAction());
				oneOf(k4).getEncoded();
				will(returnValue(key4));
			}
			oneOf(k4).erase();
			// Start the timer
			oneOf(timer).scheduleAtFixedRate(with(keyManager),
					with(any(long.class)), with(any(long.class)));
			// stop()
			// The recogniser should derive the tags for period 2
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret2, false);
			will(returnValue(k2));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k2), with((long) i));
				will(new EncodeTagAction());
				oneOf(k2).getEncoded();
				will(returnValue(key2));
			}
			oneOf(k2).erase();
			// The recogniser should remove the tags for period 3
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret3, false);
			will(returnValue(k3));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k3), with((long) i));
				will(new EncodeTagAction());
				oneOf(k3).getEncoded();
				will(returnValue(key3));
			}
			oneOf(k3).erase();
			// The recogniser should derive the tags for period 4
			oneOf(crypto).getTagCipher();
			will(returnValue(cipher));
			oneOf(crypto).deriveTagKey(secret4, false);
			will(returnValue(k4));
			for(int i = 0; i < 16; i++) {
				oneOf(crypto).encodeTag(with(any(byte[].class)), with(cipher),
						with(k4), with((long) i));
				will(new EncodeTagAction());
				oneOf(k4).getEncoded();
				will(returnValue(key4));
			}
			oneOf(k4).erase();
			// Remove the listener and stop the timer
			oneOf(db).removeListener(with(any(DatabaseListener.class)));
			oneOf(timer).cancel();
		}});

		assertTrue(keyManager.start());
		keyManager.stop();

		context.assertIsSatisfied();
	}

	private void encodeTag(byte[] tag, byte[] rawKey, long connection) {
		// Encode a fake tag based on the key and connection number
		System.arraycopy(rawKey, 0, tag, 0, tag.length);
		ByteUtils.writeUint32(connection, tag, 0);
	}

	private class EncodeTagAction implements Action {

		public void describeTo(Description description) {
			description.appendText("Encodes a tag");
		}

		public Object invoke(Invocation invocation) throws Throwable {
			byte[] tag = (byte[]) invocation.getParameter(0);
			ErasableKey key = (ErasableKey) invocation.getParameter(2);
			long connection = (Long) invocation.getParameter(3);
			byte[] rawKey = key.getEncoded();
			encodeTag(tag, rawKey, connection);
			return null;
		}
	}
}
