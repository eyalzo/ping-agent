package com.eyalzo.pingagent;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main.
 * 
 * <h1>Ping Server</h1> Central entity, normally running in wan.ninja/api/ping_announce.php. It returns a json with configuration and (optionally) list of IPs
 * to ping. Configuration is returned under "agent_configuration". For example:
 * 
 * <pre>
 	"agent_configuration": {
 		"announce_interval_sec": 60,
 		"ping_interval_sec": 60,
		"ping_timeout_ms": 500,
		"min_agent_version": 140,
		"max_agents_per_region": 2
	}
 * </pre>
 * 
 * <h1>Install and run manually (debug)</h1>
 * 
 * First, consider using /opt/cbn/install_ping_agent.sh - when it runs in a remote machine, it pulls new versions and run them. The script also runs on a
 * machine restart, so this might also be an option for you. If not applicable for the case, then try the following manual procedure:
 * 
 * <pre>
 key="~/.ssh/cbn_aws_master_key.pem"
 user="ubuntu"
 machines="40.89.142.110"
 
 # For cloudcast machines with script install (see misc folder):
machines=`curl "https://wan.ninja/announce.php?project=cbn_sagent_1&client_name=eyal&client_ip=0"|grep clients|cut -d '[' -f2|awk 'BEGIN {RS=","; FS="[\":]"} {print $2}'`
for i in $machines; do echo $i; ssh -n -f -oBatchMode=yes -o ConnectTimeout=10 -o "StrictHostKeyChecking no" -i $key -l $user $i "sh -c 'cd /opt/cbn/; sudo ./install_ping_agent.sh > /dev/null 2>&1'"; done
 
 # Copy to /tmp
 scp -o "StrictHostKeyChecking no" -i $key ~/git/cbn-ping-agent/target/ping_agent-jar-with-dependencies.jar $user@$machine:/tmp/.
 # Run from /tmp 
 ssh -n -f -o "StrictHostKeyChecking no" -o ConnectTimeout=3 -i $key -l $user $machine -l $user $i "sh -c 'nohup sudo java -jar /tmp/ping_agent-jar-with-dependencies.jar > /dev/null 2>&1 &'"
 * </pre>
 *
 * 
 * Versions:
 * <ul>
 * <li>140 2019-05-05 Eyal Zohar - First version, after copying base code from saas agent. Start with 140 because the latest python PA is 122.
 * <li>141 2019-05-13 Eyal Zohar - Config reload and updated config display.
 * <li>142 2019-05-26 Eyal Zohar - Comments and version update to test the install script auto-update feature.
 * <li>144 2019-08-18 Eyal Zohar - Clear exception message and stack trace. Display stack trace. More ping executers. Show queue time per ping. Show timeout.
 * <li>145 2019-08-19 Eyal Zohar - Implemented some executer tips from Oracle.
 * <li>146 2019-08-25 Eyal Zohar - Upgrade HttpClient to 4.5.9. Add timeout and safer close when reporting to server.
 * <li>147 2019-11-18 Eyal Zohar - LoopThread history (last 20 items).
 * <li>148 2019-11-18 Eyal Zohar - Addresses cleanup (twice the max loop of ping or announce).
 * <li>149 2019-12-16 Eyal Zohar - Add announce_thread to help. Add IP and region list to announce_thread page. String addresses cleanup, leaving only latest
 * <li>150 2019-12-18 Eyal Zohar - Act as file-server for speed test, supporting "download" command. addresses to ping.
 * <li>151 2019-12-19 Eyal Zohar - Major rewrite to announce and ping. Download code not ready yet.
 * <li>152 2019-12-19 Eyal Zohar - Download thread downloads content to null from other agents (version 150+).
 * <li>153 2019-12-25 Eyal Zohar - Fixed input stream close bug.
 * <li>154 2019-12-25 Eyal Zohar - Fixed output stream close bug.
 * <li>155 2019-12-25 Eyal Zohar - Lock lists in download thread.
 * <li>156 2019-12-25 Eyal Zohar - Fixed minor bug related to empty list for download.
 * <li>157 2020-01-01 Eyal Zohar - Ping and download executers are configurable.
 * <li>158 2021-12-05 Eyal Zohar - Move wan.ninja to https.
 * <li>159 2023-03-13 Eyal Zohar - Security fixes upon request. Mainly due to Java deserialization attack detection, that is not our fault but a bug in the detection tool, but we better "fix it" instead of arguing that the agent is fine.
 * </ul>
 * 
 * @author Eyal Zohar
 */
public class PingAgent {
	/**
	 * Version number. Must be changed on every commit with code changes.
	 */
	private static final int APP_VERSION = 159;
	/**
	 * Application name, to report to external remote entities.
	 */
	private static final String APP_NAME = "pa";
	/**
	 * The TCP port where the server listens.
	 */
	private static final int HTTP_SERVER_PORT = 5001;
	private static final int HTTP_SERVER_BACKLOG = 10;
	private static final int HTTP_SERVER_THREADS = 100;
	/**
	 * Base URL for the announce. Need to close it with announce count as number, and then the closing '}'.
	 */
	private static final String ANNOUNCE_URL_BASE = "https://wan.ninja/api/ping_announce.php?listen_port="
			+ HTTP_SERVER_PORT + "&version=" + APP_VERSION + "&client_name=";
	private static final String PING_REPORT_URL_BASE = "https://wan.ninja/report_ping.php?client_port="
			+ HTTP_SERVER_PORT + "&app=" + APP_NAME + "&version=" + APP_VERSION;
	private static final String DOWNLOAD_REPORT_URL_BASE = "https://wan.ninja/api/report_download.php?client_port="
			+ HTTP_SERVER_PORT + "&app=" + APP_NAME + "&version=" + APP_VERSION;

	private static final String LOCAL_CONFIG_FILE_NAME = "/opt/cbn/app.configs";

	public static void main(String[] args) {
		System.out.println("Ping Agent ver. " + APP_VERSION);

		//
		// Machine name
		//

		// Local configuration, for network name etc.
		LocalConfig localConfig = new LocalConfig(LOCAL_CONFIG_FILE_NAME);
		String machineName = localConfig.getMachineName();
		if (machineName == null) {
			System.err.println(
					"Failed to retrieve machine name from " + localConfig.getFileName() + ". Use system name instead.");
			machineName = PingUtils.getMachineName();
			if (machineName == null) {
				System.err.println("Failed to retrieve machine name from system!");
				System.exit(-1);
			}
		}

		// Remote configuration from the ping server
		Config config = new Config();

		//
		// Ping thread
		//
		PingThread pingThread = new PingThread(PING_REPORT_URL_BASE, config);
		pingThread.start();
		System.out.println("Started ping_thread, reporting to " + PING_REPORT_URL_BASE);

		//
		// Download thread
		//
		DownloadThread downloadThread = new DownloadThread(DOWNLOAD_REPORT_URL_BASE, config);
		downloadThread.start();
		System.out.println("Started download_thread, reporting to " + DOWNLOAD_REPORT_URL_BASE);

		//
		// Announce loop thread
		//
		String announceUrlBase = ANNOUNCE_URL_BASE + machineName + "&network_id=" + localConfig.getNetworkId()
				+ "&comment=&cloud_provider=" + localConfig.getCloudName() + "&cloud_region="
				+ localConfig.getCloudRegion();
		AnnounceThread announceThread = new AnnounceThread(announceUrlBase, pingThread, downloadThread, config);
		announceThread.start();

		//
		// HTTP server
		//

		// Use the agent's static port
		InetSocketAddress addr = new InetSocketAddress(HTTP_SERVER_PORT);
		// Try to listen (will fail if already running)
		HttpServer httpServer = null;
		try {
			httpServer = HttpServer.create(addr, HTTP_SERVER_BACKLOG);
		} catch (IOException e) {
			System.out.println("Cannot listen to " + addr + ". Error: " + e);
			System.exit(-1);
		}
		// Start running
		System.out.println("Listen on \"" + machineName + "\" " + addr);
		httpServer.createContext("/",
				new PingHttpHandler(APP_VERSION, announceThread, pingThread, downloadThread, localConfig));
		ExecutorService pool = Executors.newFixedThreadPool(HTTP_SERVER_THREADS);
		httpServer.setExecutor(pool);
		httpServer.start();
	}
}