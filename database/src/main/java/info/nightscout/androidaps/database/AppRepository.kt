package info.nightscout.androidaps.database

import info.nightscout.androidaps.database.entities.GlucoseValue
import info.nightscout.androidaps.database.entities.TemporaryTarget
import info.nightscout.androidaps.database.interfaces.DBEntry
import info.nightscout.androidaps.database.transactions.Transaction
import io.reactivex.Completable
import io.reactivex.Maybe
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.Callable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppRepository @Inject internal constructor(
    internal val database: AppDatabase
) {

    private val changeSubject = PublishSubject.create<List<DBEntry>>()

    fun changeObservable(): Observable<List<DBEntry>> = changeSubject.subscribeOn(Schedulers.io())

    val databaseVersion = DATABASE_VERSION

    /**
     * Executes a transaction ignoring its result
     * Runs on IO scheduler
     */
    fun <T> runTransaction(transaction: Transaction<T>): Completable {
        val changes = mutableListOf<DBEntry>()
        return Completable.fromCallable {
            database.runInTransaction {
                transaction.database = DelegatedAppDatabase(changes, database)
                transaction.run()
            }
        }.subscribeOn(Schedulers.io()).doOnComplete {
            changeSubject.onNext(changes)
        }
    }

    /**
     * Executes a transaction and returns its result
     * Runs on IO scheduler
     */
    fun <T> runTransactionForResult(transaction: Transaction<T>): Single<T> {
        val changes = mutableListOf<DBEntry>()
        return Single.fromCallable {
            database.runInTransaction(Callable<T> {
                transaction.database = DelegatedAppDatabase(changes, database)
                transaction.run()
            })
        }.subscribeOn(Schedulers.io()).doOnSuccess {
            changeSubject.onNext(changes)
        }
    }

    fun clearDatabases() = database.clearAllTables()

    //BG READINGS -- only valid records
    fun compatGetBgReadingsDataFromTime(timestamp: Long, ascending: Boolean) =
        database.glucoseValueDao.compatGetBgReadingsDataAfterTime(timestamp)
            .map { if (!ascending) it.reversed() else it }
            .subscribeOn(Schedulers.io())

    fun compatGetBgReadingsDataFromTime(start: Long, end: Long, ascending: Boolean) =
        database.glucoseValueDao.compatGetBgReadingsDataAfterTime(start, end)
            .map { if (!ascending) it.reversed() else it }
            .subscribeOn(Schedulers.io())

    //BG READINGS -- including invalid/history records
    fun findBgReadingByNSIdSingle(nsId: String): Single<ValueWrapper<GlucoseValue>> =
        database.glucoseValueDao.findByNSIdMaybe(nsId).toWrappedSingle()

    fun getModifiedBgReadingsDataAfterId(lastId: Long) =
        database.glucoseValueDao.getModifiedAfter(lastId)
            .subscribeOn(Schedulers.io())

    fun getBgReadingsCorrespondingLastHistoryRecord(lastId: Long) =
        database.glucoseValueDao.getLastHistoryRecord(lastId)

    fun getBgReadingsCorrespondingLastHistoryRecordSingle(lastId: Long) =
        database.glucoseValueDao.getLastHistoryRecordMaybe(lastId).toWrappedSingle()

    @Suppress("unused") // debug purpose only
    fun getAllBgReadingsStartingFrom(lastId: Long) =
        database.glucoseValueDao.getAllStartingFrom(lastId)
            .subscribeOn(Schedulers.io())

    // TEMP TARGETS
    fun compatGetTemporaryTargetDataFromTime(timestamp: Long, ascending: Boolean) =
        database.temporaryTargetDao.compatGetTemporaryTargetDataFromTime(timestamp)
            .map { if (!ascending) it.reversed() else it }
            .subscribeOn(Schedulers.io())

    fun findTemporaryTargetByNSIdSingle(nsId: String): Single<ValueWrapper<TemporaryTarget>> =
        database.temporaryTargetDao.findByNSIdMaybe(nsId).toWrappedSingle()

    fun getModifiedTemporaryTargetsDataFromId(lastId: Long) =
        database.temporaryTargetDao.getModifiedFrom(lastId)
            .subscribeOn(Schedulers.io())

    fun getTemporaryTargetsCorrespondingLastHistoryRecord(lastId: Long) =
        database.temporaryTargetDao.getLastHistoryRecord(lastId)

}

inline fun <reified T> Maybe<T>.toWrappedSingle(): Single<ValueWrapper<T>> =
    this.map { ValueWrapper.Existing(it) as ValueWrapper<T> }
        .switchIfEmpty(Maybe.just(ValueWrapper.Absent()))
        .toSingle()

sealed class ValueWrapper<T> {
    data class Existing<T>(val value: T) : ValueWrapper<T>()
    class Absent<T> : ValueWrapper<T>()
}