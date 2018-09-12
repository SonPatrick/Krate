package io.lamart.krate.database

import android.content.ContentValues
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import io.lamart.krate.Interceptor
import io.lamart.krate.Krate
import io.lamart.krate.Serializer
import io.lamart.krate.database.Constants.Companion.KEY
import io.lamart.krate.database.Constants.Companion.MODIFIED
import io.lamart.krate.database.Constants.Companion.VALUE
import io.lamart.krate.utils.use
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Maybe
import io.reactivex.Single
import io.reactivex.processors.PublishProcessor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * This Krate is associated with a database. It will create one table, if it does not exists, that contains a column for the key, value and modified time.
 *
 * @param tableName The table name that will be used as a name for the single table.
 * @param serializer Serializes and deserializes values to and from bytes respectively.
 * @param interceptor Optionally manipulates the bytes.
 */

class DatabaseKrate(
        private val database: SQLiteDatabase,
        private val tableName: String = "krate_data",
        private val serializer: Serializer = Serializer.Default(),
        private val interceptor: Interceptor = Interceptor.Default
) : Krate, Constants {

    private val processor = PublishProcessor.create<String>()

    init {
        database.execSQL(
                "CREATE TABLE IF NOT EXISTS $tableName (" +
                        "$KEY TEXT UNIQUE PRIMARY KEY NOT NULL," +
                        "$MODIFIED LONG NOT NULL," +
                        "$VALUE BLOB NOT NULL" +
                        ");" +
                        "CREATE UNIQUE INDEX IF NOT EXISTS ${tableName}_${KEY}_index ON $tableName ($KEY);"
        )
    }

    override fun getKeys(): Single<Collection<String>> =
            Single.fromCallable {
                query(arrayOf(KEY)).use { cursor ->
                    cursor.map { it.key }
                }
            }

    override fun getModifieds(): Single<Map<String, Long>> =
            Single.fromCallable {
                query(arrayOf(KEY, MODIFIED)).use { cursor ->
                    cursor.map { it.key to it.modified }.toMap()
                }
            }

    override fun observe(): Flowable<String> = processor

    override fun <T> get(key: String): Maybe<T> =
            Maybe.fromCallable<T> {
                query(key).takeIf { it.moveToFirst() }
                        ?.value
                        ?.let(::ByteArrayInputStream)
                        ?.let { interceptor.read(key, it) }
                        ?.use { serializer.read(key, it) }
            }

    override fun <T> getAndFetch(key: String, fetch: () -> Single<T>): Flowable<T> =
            Maybe.concat(
                    get<T>(key),
                    fetch().flatMap { put(key, it).toSingleDefault(it) }.toMaybe()
            )

    override fun <T> getOrFetch(key: String, fetch: (modified: Long) -> Maybe<T>): Flowable<T> =
            Maybe.concat(
                    get<T>(key),
                    getModified(key)
                            .let(fetch)
                            .flatMapSingleElement { put(key, it).toSingleDefault(it) }
            )

    override fun remove(key: String): Completable =
            Completable.fromAction {
                database.delete(tableName, "$KEY == ?", arrayOf(key))
            }

    override fun <T> put(key: String, value: T): Completable =
            Completable
                    .fromAction {
                        database.insertWithOnConflict(
                                tableName,
                                KEY,
                                getValues(key, value),
                                SQLiteDatabase.CONFLICT_REPLACE
                        )
                    }
                    .doOnComplete { processor.onNext(key) }

    private fun <T> getValues(key: String, value: T): ContentValues =
            ContentValues(4).apply {
                put(KEY, key)
                put(MODIFIED, System.currentTimeMillis())
                put(VALUE, serialize(key, value))
            }

    private fun <T> serialize(key: String, value: T): ByteArray =
            ByteArrayOutputStream()
                    .also { output ->
                        interceptor
                                .write(key, output)
                                .use { serializer.write(key, value, it) }
                    }
                    .toByteArray()

    private fun query(key: String, columns: Array<String>? = null): KrateCursor =
            database.queryWithFactory(
                    { _, driver, editTable, query -> SQLiteCursor(driver, editTable, query).let(::KrateCursor) },
                    false,
                    tableName,
                    columns,
                    "$KEY == ?",
                    arrayOf(key),
                    null,
                    null,
                    null,
                    null
            ) as KrateCursor

    private fun query(columns: Array<String>? = null): KrateCursor =
            database.queryWithFactory(
                    { _, driver, editTable, query -> SQLiteCursor(driver, editTable, query).let(::KrateCursor) },
                    false,
                    tableName,
                    columns,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            ) as KrateCursor

    private fun getModified(key: String): Long =
            query(key, arrayOf(MODIFIED)).takeIf { it.moveToFirst() }?.modified ?: Long.MIN_VALUE

}

