package org.briarproject.bramble.plugin.tcp;

import org.briarproject.bramble.PoliteExecutor;
import org.briarproject.bramble.api.Pair;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.keyagreement.KeyAgreementListener;
import org.briarproject.bramble.api.nullsafety.MethodsNotNullByDefault;
import org.briarproject.bramble.api.nullsafety.NotNullByDefault;
import org.briarproject.bramble.api.nullsafety.ParametersNotNullByDefault;
import org.briarproject.bramble.api.plugin.Backoff;
import org.briarproject.bramble.api.plugin.ConnectionHandler;
import org.briarproject.bramble.api.plugin.PluginCallback;
import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.rendezvous.KeyMaterialSource;
import org.briarproject.bramble.api.rendezvous.RendezvousEndpoint;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import static java.net.NetworkInterface.getNetworkInterfaces;
import static java.util.Collections.emptyList;
import static java.util.Collections.list;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE;
import static org.briarproject.bramble.api.plugin.Plugin.State.DISABLED;
import static org.briarproject.bramble.api.plugin.Plugin.State.INACTIVE;
import static org.briarproject.bramble.util.IoUtils.tryToClose;
import static org.briarproject.bramble.util.LogUtils.logException;
import static org.briarproject.bramble.util.PrivacyUtils.scrubSocketAddress;
import static org.briarproject.bramble.util.StringUtils.isNullOrEmpty;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
abstract class TcpPlugin implements DuplexPlugin {

	private static final Logger LOG = getLogger(TcpPlugin.class.getName());

	private static final Pattern DOTTED_QUAD =
			Pattern.compile("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");

	protected final Executor ioExecutor, bindExecutor;
	protected final Backoff backoff;
	protected final PluginCallback callback;
	protected final int maxLatency, maxIdleTime, socketTimeout;
	protected final AtomicBoolean used = new AtomicBoolean(false);
	protected final PluginState state = new PluginState();

	/**
	 * Returns zero or more socket addresses on which the plugin should listen,
	 * in order of preference. At most one of the addresses will be bound.
	 */
	protected abstract List<InetSocketAddress> getLocalSocketAddresses();

	/**
	 * Adds the address on which the plugin is listening to the transport
	 * properties.
	 */
	protected abstract void setLocalSocketAddress(InetSocketAddress a);

	/**
	 * Returns zero or more socket addresses for connecting to a contact with
	 * the given transport properties.
	 */
	protected abstract List<InetSocketAddress> getRemoteSocketAddresses(
			TransportProperties p);

	/**
	 * Returns true if connections to the given address can be attempted.
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	protected abstract boolean isConnectable(InetSocketAddress remote);

	TcpPlugin(Executor ioExecutor, Backoff backoff, PluginCallback callback,
			int maxLatency, int maxIdleTime) {
		this.ioExecutor = ioExecutor;
		this.backoff = backoff;
		this.callback = callback;
		this.maxLatency = maxLatency;
		this.maxIdleTime = maxIdleTime;
		if (maxIdleTime > Integer.MAX_VALUE / 2)
			socketTimeout = Integer.MAX_VALUE;
		else socketTimeout = maxIdleTime * 2;
		// Don't execute more than one bind operation at a time
		bindExecutor = new PoliteExecutor("TcpPlugin", ioExecutor, 1);
	}

	@Override
	public int getMaxLatency() {
		return maxLatency;
	}

	@Override
	public int getMaxIdleTime() {
		return maxIdleTime;
	}

	@Override
	public void start() {
		if (used.getAndSet(true)) throw new IllegalStateException();
		state.setStarted();
		callback.pluginStateChanged(getState());
		bind();
	}

	protected void bind() {
		bindExecutor.execute(() -> {
			if (getState() != INACTIVE) return;
			ServerSocket ss = null;
			for (InetSocketAddress addr : getLocalSocketAddresses()) {
				try {
					ss = new ServerSocket();
					ss.bind(addr);
					break;
				} catch (IOException e) {
					if (LOG.isLoggable(INFO))
						LOG.info("Failed to bind " + scrubSocketAddress(addr));
					tryToClose(ss, LOG, WARNING);
				}
			}
			if (ss == null) {
				LOG.info("Could not bind server socket");
				return;
			}
			if (!state.setServerSocket(ss)) {
				LOG.info("Closing redundant server socket");
				tryToClose(ss, LOG, WARNING);
				return;
			}
			backoff.reset();
			InetSocketAddress local =
					(InetSocketAddress) ss.getLocalSocketAddress();
			setLocalSocketAddress(local);
			callback.pluginStateChanged(getState());
			if (LOG.isLoggable(INFO))
				LOG.info("Listening on " + scrubSocketAddress(local));
			acceptContactConnections(ss);
		});
	}

	String getIpPortString(InetSocketAddress a) {
		String addr = a.getAddress().getHostAddress();
		int percent = addr.indexOf('%');
		if (percent != -1) addr = addr.substring(0, percent);
		return addr + ":" + a.getPort();
	}

	private void acceptContactConnections(ServerSocket ss) {
		while (true) {
			Socket s;
			try {
				s = ss.accept();
				s.setSoTimeout(socketTimeout);
			} catch (IOException e) {
				// This is expected when the server socket is closed
				LOG.info("Server socket closed");
				state.clearServerSocket(ss);
				callback.pluginStateChanged(getState());
				return;
			}
			if (LOG.isLoggable(INFO))
				LOG.info("Connection from " +
						scrubSocketAddress(s.getRemoteSocketAddress()));
			backoff.reset();
			callback.handleConnection(new TcpTransportConnection(this, s));
		}
	}

	@Override
	public void stop() {
		ServerSocket ss = state.setStopped();
		callback.pluginStateChanged(getState());
		tryToClose(ss, LOG, WARNING);
	}

	@Override
	public State getState() {
		return state.getState();
	}

	@Override
	public int getReasonDisabled() {
		return getState() == DISABLED ? REASON_STARTING_STOPPING : -1;
	}

	@Override
	public boolean shouldPoll() {
		return true;
	}

	@Override
	public int getPollingInterval() {
		return backoff.getPollingInterval();
	}

	@Override
	public void poll(Collection<Pair<TransportProperties, ConnectionHandler>>
			properties) {
		if (getState() != ACTIVE) return;
		backoff.increment();
		for (Pair<TransportProperties, ConnectionHandler> p : properties) {
			connect(p.getFirst(), p.getSecond());
		}
	}

	private void connect(TransportProperties p, ConnectionHandler h) {
		ioExecutor.execute(() -> {
			DuplexTransportConnection d = createConnection(p);
			if (d != null) {
				backoff.reset();
				h.handleConnection(d);
			}
		});
	}

	@Override
	public DuplexTransportConnection createConnection(TransportProperties p) {
		ServerSocket ss = state.getServerSocket();
		if (ss == null) return null;
		for (InetSocketAddress remote : getRemoteSocketAddresses(p)) {
			if (!isConnectable(remote)) {
				if (LOG.isLoggable(INFO)) {
					LOG.info(scrubSocketAddress(remote) +
							" is not connectable from " +
							scrubSocketAddress(ss.getLocalSocketAddress()));
				}
				continue;
			}
			try {
				if (LOG.isLoggable(INFO))
					LOG.info("Connecting to " + scrubSocketAddress(remote));
				Socket s = createSocket();
				s.bind(new InetSocketAddress(ss.getInetAddress(), 0));
				s.connect(remote);
				s.setSoTimeout(socketTimeout);
				if (LOG.isLoggable(INFO))
					LOG.info("Connected to " + scrubSocketAddress(remote));
				return new TcpTransportConnection(this, s);
			} catch (IOException e) {
				if (LOG.isLoggable(INFO))
					LOG.info("Could not connect to " +
							scrubSocketAddress(remote));
			}
		}
		return null;
	}

	protected Socket createSocket() throws IOException {
		return new Socket();
	}

	@Nullable
	InetSocketAddress parseSocketAddress(String ipPort) {
		if (isNullOrEmpty(ipPort)) return null;
		String[] split = ipPort.split(":");
		if (split.length != 2) return null;
		String addr = split[0], port = split[1];
		// Ensure getByName() won't perform a DNS lookup
		if (!DOTTED_QUAD.matcher(addr).matches()) return null;
		try {
			InetAddress a = InetAddress.getByName(addr);
			int p = Integer.parseInt(port);
			return new InetSocketAddress(a, p);
		} catch (UnknownHostException e) {
			if (LOG.isLoggable(WARNING))
				// not scrubbing to enable us to find the problem
				LOG.warning("Invalid address: " + addr);
			return null;
		} catch (NumberFormatException e) {
			if (LOG.isLoggable(WARNING))
				LOG.warning("Invalid port: " + port);
			return null;
		}
	}

	@Override
	public boolean supportsKeyAgreement() {
		return false;
	}

	@Override
	public KeyAgreementListener createKeyAgreementListener(byte[] commitment) {
		throw new UnsupportedOperationException();
	}

	@Override
	public DuplexTransportConnection createKeyAgreementConnection(
			byte[] commitment, BdfList descriptor) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean supportsRendezvous() {
		return false;
	}

	@Override
	public RendezvousEndpoint createRendezvousEndpoint(KeyMaterialSource k,
			boolean alice, ConnectionHandler incoming) {
		throw new UnsupportedOperationException();
	}

	Collection<InetAddress> getLocalIpAddresses() {
		try {
			Enumeration<NetworkInterface> ifaces = getNetworkInterfaces();
			if (ifaces == null) return emptyList();
			List<InetAddress> addrs = new ArrayList<>();
			for (NetworkInterface iface : list(ifaces))
				addrs.addAll(list(iface.getInetAddresses()));
			return addrs;
		} catch (SocketException e) {
			logException(LOG, WARNING, e);
			return emptyList();
		}
	}

	@ThreadSafe
	@NotNullByDefault
	protected static class PluginState {

		@GuardedBy("this")
		private boolean started = false, stopped = false;
		@GuardedBy("this")
		@Nullable
		private ServerSocket serverSocket = null;

		synchronized void setStarted() {
			started = true;
		}

		@Nullable
		synchronized ServerSocket setStopped() {
			stopped = true;
			ServerSocket ss = serverSocket;
			serverSocket = null;
			return ss;
		}

		@Nullable
		synchronized ServerSocket getServerSocket() {
			return serverSocket;
		}

		synchronized boolean setServerSocket(ServerSocket ss) {
			if (stopped || serverSocket != null) return false;
			serverSocket = ss;
			return true;
		}

		synchronized void clearServerSocket(ServerSocket ss) {
			if (serverSocket == ss) serverSocket = null;
		}

		synchronized State getState() {
			if (!started || stopped) return DISABLED;
			return serverSocket == null ? INACTIVE : ACTIVE;
		}
	}
}
