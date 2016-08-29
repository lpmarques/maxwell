package com.zendesk.maxwell.recovery;

import com.zendesk.maxwell.*;
import com.zendesk.maxwell.schema.MysqlSchemaStore;
import com.zendesk.maxwell.schema.SchemaStoreSchema;
import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class RecoveryTest {
	private static MysqlIsolatedServer masterServer, slaveServer;

	@BeforeClass
	public static void setupSlaveServer() throws Exception {
		masterServer = new MysqlIsolatedServer();
		masterServer.boot();
		SchemaStoreSchema.ensureMaxwellSchema(masterServer.getConnection(), "maxwell");

		slaveServer = MaxwellTestSupport.setupServer("--server_id=12345 --max_binlog_size=100000 --log_bin=slave");
		slaveServer.setupSlave(masterServer.getPort());

		MaxwellTestSupport.setupSchema(masterServer, false);
	}

	private MaxwellConfig getConfig(int port) {
		MaxwellConfig config = new MaxwellConfig();
		config.maxwellMysql.host = "localhost";
		config.maxwellMysql.port = port;
		config.maxwellMysql.user = "maxwell";
		config.maxwellMysql.password = "maxwell";
		config.validate();
		return config;
	}

	private MaxwellContext getContext(int port) throws SQLException {
		MaxwellConfig config = getConfig(port);
		return new MaxwellContext(config);
	}

	private String[] generateMasterData() throws Exception {
		String input[] = new String[5000];
		for ( int i = 0 ; i < 5000; i++ ) {
			input[i] = String.format("insert into shard_1.minimal set account_id = %d, text_field='row %d'", i, i);
		}
		return input;
	}

	private void generateNewMasterData() throws Exception {
		for ( int i = 0 ; i < 1000; i++ ) {
			slaveServer.execute(String.format("insert into shard_1.minimal set account_id = %d, text_field='row %d'", i + 5000, i + 5000));
			if ( i % 100 == 0 )
				slaveServer.execute("flush logs");
		}
	}

	@Test
	public void testBasicRecovery() throws Exception {
		MaxwellContext slaveContext = getContext(slaveServer.getPort());

		String[] input = generateMasterData();
		/* run the execution through with the replicator running so we get heartbeats */
		MaxwellTestSupport.getRowsWithReplicator(masterServer, null, input, null);

		BinlogPosition slavePosition = BinlogPosition.capture(slaveServer.getConnection());

		generateNewMasterData();
		RecoveryInfo recoveryInfo = slaveContext.getRecoveryInfo();

		MaxwellConfig slaveConfig = getConfig(slaveServer.getPort());
		Recovery recovery = new Recovery(
			slaveConfig.maxwellMysql,
			slaveConfig.databaseName,
			slaveContext.getReplicationConnectionPool(),
			slaveContext.getCaseSensitivity(),
			recoveryInfo
		);

		BinlogPosition recoveredPosition = recovery.recover();
		// lousy tests, but it's very hard to make firm assertions about the correct position.
		// It's in a ballpark.

		if ( slavePosition.getFile().equals(recoveredPosition.getFile()) )	{
			long positionDiff = recoveredPosition.getOffset() - slavePosition.getOffset();
			assertThat(Math.abs(positionDiff), lessThan(1000L));
		} else {
			// TODO: something something.
		}
	}

	@Test
	public void testRecoveryIntegration() throws Exception {
		String[] input = generateMasterData();
		/* run the execution through with the replicator running so we get heartbeats */
		List<RowMap> rows = MaxwellTestSupport.getRowsWithReplicator(masterServer, null, input, null);

		generateNewMasterData();

		MaxwellConfig c = getConfig(slaveServer.getPort());
		BufferedMaxwell maxwell = new BufferedMaxwell(getConfig(slaveServer.getPort()));

		new Thread(maxwell).start();

		for ( ;; ) {
			RowMap r = maxwell.getRow(10, TimeUnit.SECONDS);
			if ( r == null )
				break;
			else
				rows.add(r);
		}

		for ( long i = 0 ; i < 6000; i++ ) {
			assertEquals(i + 1, rows.get((int) i).getData("id"));
		}
	}
}
