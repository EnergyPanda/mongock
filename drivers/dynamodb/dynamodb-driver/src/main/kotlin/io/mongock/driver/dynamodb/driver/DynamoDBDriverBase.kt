package io.mongock.driver.dynamodb.driver

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput
import com.amazonaws.services.dynamodbv2.model.ReturnConsumedCapacity
import com.amazonaws.services.dynamodbv2.model.TransactWriteItemsRequest
import io.mongock.api.exception.MongockException
import io.mongock.driver.api.driver.ChangeSetDependency
import io.mongock.driver.api.driver.Transactional
import io.mongock.driver.api.entry.ChangeEntryService
import io.mongock.driver.core.driver.TransactionalConnectionDriverBase
import io.mongock.driver.core.lock.LockRepository
import io.mongock.driver.dynamodb.repository.DynamoDBChangeEntryRepository
import io.mongock.driver.dynamodb.repository.DynamoDBLockRepository
import io.mongock.driver.dynamodb.repository.DynamoDBTransactionItems
import mu.KotlinLogging
import java.util.Optional

private val logger = KotlinLogging.logger {}

abstract class DynamoDBDriverBase protected constructor(
    private val client: AmazonDynamoDBClient,
    lockAcquiredForMillis: Long,
    lockQuitTryingAfterMillis: Long,
    lockTryFrequencyMillis: Long
) : TransactionalConnectionDriverBase(lockAcquiredForMillis, lockQuitTryingAfterMillis, lockTryFrequencyMillis),
    Transactional {

    var provisionedThroughput: ProvisionedThroughput? = ProvisionedThroughput(50L, 50L)

    private val _dependencies: MutableSet<ChangeSetDependency> = HashSet()

    private val _changeEntryService: DynamoDBChangeEntryRepository by lazy {
        DynamoDBChangeEntryRepository(
            client,
            getMigrationRepositoryName(),
            indexCreation,
            provisionedThroughput
        )
    }

    private val _lockRepository: DynamoDBLockRepository by lazy {
        DynamoDBLockRepository(
            client,
            getLockRepositoryName(),
            indexCreation,
            if (provisionedThroughput != null) ProvisionedThroughput(1L, 1L) else null
        )
    }

    private var transactionItems: DynamoDBTransactionItems? = null

    override fun getLockRepository(): LockRepository {
        return _lockRepository
    }

    override fun getChangeEntryService(): ChangeEntryService {
        return _changeEntryService
    }

    override fun getTransactioner(): Optional<Transactional> {
        return Optional.ofNullable(if (transactionEnabled) this else null)
    }

    override fun getDependencies(): Set<ChangeSetDependency> {
        val currentTransactionItemsOpt = _dependencies.stream()
            .filter { it.type == DynamoDBTransactionItems::class.java }
            .findAny()
        if (currentTransactionItemsOpt.isPresent) {
            _dependencies.remove(currentTransactionItemsOpt.get());
        }

        if (transactionItems != null) {
            _dependencies.add(ChangeSetDependency(DynamoDBTransactionItems::class.java, transactionItems, true))
        }
        return _dependencies;
    }

    override fun getNonProxyableTypes(): List<Class<*>> {
        return listOf(DynamoDBMapper::class.java, DynamoDBTransactionItems::class.java)
    }

    //TODO potentially removable(move it to executeInTransaction)
    override fun prepareForExecutionBlock() {
        transactionItems = DynamoDBTransactionItems()
    }

    override fun executeInTransaction(operation: Runnable) {
        try {
            _changeEntryService.transactionItems = transactionItems
            operation.run()
            if (!transactionItems!!.containsUserTransactions()) {
                logger.debug { "no transaction items for changeUnit" }
            }
            // Is possible it doesn't contain any transaction, for example when the changeItem is ignored
            if (transactionItems!!.containsAnyTransaction()) {
                val result = client.transactWriteItems(
                    TransactWriteItemsRequest()
                        .withTransactItems(transactionItems!!.items)
                        .withReturnConsumedCapacity(ReturnConsumedCapacity.TOTAL)
                )
                logger.debug { "DynamoDB transaction successful: $result" }
            }
        } catch (ex: Throwable) {
            throw MongockException(ex)
        } finally {
            _changeEntryService.cleanTransactionRequest();
            transactionItems = null
            removeDependencyIfAssignableFrom(_dependencies, DynamoDBTransactionItems::class.java)
        }
    }
}