package org.sunbird.kernel.extension;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.event.TransactionData;
import org.neo4j.graphdb.event.TransactionEventHandler;


@SuppressWarnings("rawtypes")
public class EkStepTransactionEventHandler implements TransactionEventHandler {

	public static GraphDatabaseService db;
    private Logger logger = LogManager.getLogger("org.sunbird.kernel.extension.EkStepTransactionEventHandler");

	public EkStepTransactionEventHandler(GraphDatabaseService graphDatabaseService) {
		db =  graphDatabaseService;
	}

	@Override
	public Void beforeCommit(TransactionData transactionData) throws Exception {
		try {
			ProcessTransactionData processTransactionData = new ProcessTransactionData(
					"domain", db);
			processTransactionData.processTxnData(transactionData);
		} catch (Exception e) {
			throw e;
		}
		return null;
	}

	@Override
	public void afterCommit(TransactionData transactionData, Object o) {
		logger.info("After Commit Executed.");
	}

	@Override
	public void afterRollback(TransactionData transactionData, Object o) {
		logger.info("After Rollback Executed.");
	}
}
