/**
 * Copyright 2010 - 2020 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("NAME_SHADOWING")

package jetbrains.exodus.entitystore

import jetbrains.exodus.ArrayByteIterable
import jetbrains.exodus.ByteIterable
import jetbrains.exodus.bindings.*
import jetbrains.exodus.core.dataStructures.hash.*
import jetbrains.exodus.core.dataStructures.persistent.PersistentLong23TreeSet
import jetbrains.exodus.core.dataStructures.persistent.PersistentLongSet
import jetbrains.exodus.entitystore.tables.*
import jetbrains.exodus.env.Cursor
import jetbrains.exodus.env.ReadonlyTransactionException
import jetbrains.exodus.env.Store
import jetbrains.exodus.env.StoreConfig
import mu.KLogging
import java.util.*
import kotlin.experimental.xor

internal class PersistentEntityStoreRefactorings(private val store: PersistentEntityStoreImpl) {

    fun refactorDeleteRedundantBlobs() {
        val blobVault = store.blobVault
        if (blobVault is FileSystemBlobVaultOld) {
            logInfo("Deleting redundant blobs...")
            val nextBlobHandle = store.computeInReadonlyTransaction { tx ->
                val txn = tx as PersistentStoreTransaction
                blobVault.nextHandle(txn.environmentTransaction)
            }
            for (i in 0..9999) {
                val item = blobVault.getBlob(nextBlobHandle + i)
                if (item.exists()) {
                    if (blobVault.delete(item.handle)) {
                        logInfo("Deleted $item")
                    } else {
                        logger.error("Failed to delete $item")
                    }
                }
            }
        }
    }

    fun refactorEntitiesTables() {
        store.executeInReadonlyTransaction { tx ->
            val txn = tx as PersistentStoreTransaction
            for (entityType in store.getEntityTypes(txn)) {
                val entityTypeId = store.getEntityTypeId(txn, entityType!!, false)
                val sourceName = store.namingRules.getEntitiesTableName(entityTypeId)
                val targetName = sourceName + "_temp"
                logInfo("Refactoring $sourceName to key-prefixed store.")
                transactionalCopyAndRemoveEntitiesStore(sourceName, targetName)
                transactionalCopyAndRemoveEntitiesStore(targetName, sourceName)
            }
        }
    }

    fun refactorCreateNullPropertyIndices() {
        store.executeInReadonlyTransaction { tx ->
            val txn = tx as PersistentStoreTransaction
            for (entityType in store.getEntityTypes(txn)) {
                logInfo("Refactoring creating null-value property indices for [$entityType]")
                safeExecuteRefactoringForEntityType(entityType,
                        StoreTransactionalExecutable { tx ->
                            val txn = tx as PersistentStoreTransaction
                            val entityTypeId = store.getEntityTypeId(txn, entityType, false)
                            val props = store.getPropertiesTable(txn, entityTypeId)
                            val allPropsIndex = props.allPropsIndex
                            val cursor = store.getPrimaryPropertyIndexCursor(txn, entityTypeId)
                            val envTxn = txn.environmentTransaction
                            while (cursor.next) {
                                val propertyKey = PropertyKey.entryToPropertyKey(cursor.key)
                                allPropsIndex.put(envTxn,
                                        IntegerBinding.intToCompressedEntry(propertyKey.propertyId),
                                        LongBinding.longToCompressedEntry(propertyKey.entityLocalId))
                            }
                            cursor.close()
                        }
                )
            }
        }
    }

    fun refactorCreateNullBlobIndices() {
        store.executeInReadonlyTransaction { tx ->
            val txn = tx as PersistentStoreTransaction
            for (entityType in store.getEntityTypes(txn)) {
                logInfo("Refactoring creating null-value blob indices for [$entityType]")
                safeExecuteRefactoringForEntityType(entityType,
                        StoreTransactionalExecutable { tx ->
                            val txn = tx as PersistentStoreTransaction
                            val entityTypeId = store.getEntityTypeId(txn, entityType, false)
                            val blobs = store.getBlobsTable(txn, entityTypeId)
                            val allBlobsIndex = blobs.allBlobsIndex
                            val envTxn = txn.environmentTransaction
                            val cursor = blobs.primaryIndex.openCursor(envTxn)
                            while (cursor.next) {
                                val propertyKey = PropertyKey.entryToPropertyKey(cursor.key)
                                allBlobsIndex.put(envTxn,
                                        IntegerBinding.intToCompressedEntry(propertyKey.propertyId),
                                        LongBinding.longToCompressedEntry(propertyKey.entityLocalId))
                            }
                            cursor.close()
                        }
                )
            }
        }
    }

    fun refactorBlobFileLengths() {
        val blobVault = store.blobVault
        if (blobVault is DiskBasedBlobVault) {
            val diskVault = blobVault as DiskBasedBlobVault
            store.executeInReadonlyTransaction { tx ->
                val txn = tx as PersistentStoreTransaction
                for (entityType in store.getEntityTypes(txn)) {
                    logInfo("Refactoring blob lengths table for [$entityType]")
                    safeExecuteRefactoringForEntityType(entityType,
                            StoreTransactionalExecutable { tx ->
                                val txn = tx as PersistentStoreTransaction
                                val entityTypeId = store.getEntityTypeId(txn, entityType, false)
                                val blobs = store.getBlobsTable(txn, entityTypeId)
                                val envTxn = txn.environmentTransaction
                                blobs.primaryIndex.openCursor(envTxn).use { cursor ->
                                    while (cursor.next) {
                                        val blobHandle = LongBinding.compressedEntryToLong(cursor.value)
                                        if (!PersistentEntityStoreImpl.isEmptyOrInPlaceBlobHandle(blobHandle)) {
                                            store.setBlobFileLength(txn, blobHandle,
                                                    diskVault.getBlobLocation(blobHandle).length())
                                        }
                                    }
                                }
                            }
                    )
                }
            }
        }
    }

    fun refactorCreateNullLinkIndices() {
        store.executeInReadonlyTransaction { tx ->
            val txn = tx as PersistentStoreTransaction
            for (entityType in store.getEntityTypes(txn)) {
                logInfo("Refactoring creating null-value link indices for [$entityType]")
                safeExclusiveExecuteRefactoringForEntityType(entityType,
                        object : StoreTransactionalExecutable {
                            override fun execute(tx: StoreTransaction) {
                                val txn = tx as PersistentStoreTransaction
                                val entityTypeId = store.getEntityTypeId(txn, entityType, false)
                                var links = store.getLinksTable(txn, entityTypeId)
                                var allLinksIndex = links.allLinksIndex
                                val envTxn = txn.environmentTransaction
                                if (allLinksIndex.count(envTxn) > 0) {
                                    logger.warn("Refactoring creating null-value link indices looped for [$entityType]")
                                    envTxn.environment.truncateStore(allLinksIndex.name, envTxn)
                                    store.linksTables.remove(entityTypeId)
                                    links = store.getLinksTable(txn, entityTypeId)
                                    allLinksIndex = links.allLinksIndex
                                }
                                val readonlySnapshot = envTxn.readonlySnapshot
                                try {
                                    val cursor = links.getSecondIndexCursor(readonlySnapshot)
                                    val total = links.getSecondaryCount(readonlySnapshot)
                                    var done: Long = 0
                                    var prevLinkId = -1
                                    val idSet = PersistentLong23TreeSet().beginWrite()
                                    val format = "done %4.1f%% for $entityType"
                                    while (cursor.next) {
                                        val linkKey = PropertyKey.entryToPropertyKey(cursor.value)
                                        val linkId = linkKey.propertyId
                                        val entityLocalId = linkKey.entityLocalId
                                        if (prevLinkId != linkId) {
                                            if (prevLinkId == -1) {
                                                prevLinkId = linkId
                                            } else {
                                                if (linkId < prevLinkId) {
                                                    throw IllegalStateException("Unsorted index")
                                                }
                                                done = dumpSetAndFlush(format, allLinksIndex, txn, total.toDouble(), done, prevLinkId, idSet)
                                                prevLinkId = linkId
                                            }
                                        }
                                        idSet.add(entityLocalId)
                                    }
                                    if (prevLinkId != -1) {
                                        dumpSetAndFlush(format, allLinksIndex, txn, total.toDouble(), done, prevLinkId, idSet)
                                    }
                                    cursor.close()
                                } finally {
                                    readonlySnapshot.abort()
                                }
                            }

                            private fun dumpSetAndFlush(format: String, allLinksIndex: Store, txn: PersistentStoreTransaction, total: Double, done: Long, prevLinkId: Int, idSet: PersistentLongSet.MutableSet): Long {
                                var done = done
                                val itr = idSet.longIterator()
                                while (itr.hasNext()) {
                                    allLinksIndex.putRight(txn.environmentTransaction, IntegerBinding.intToCompressedEntry(prevLinkId), LongBinding.longToCompressedEntry(itr.next()))
                                    done++
                                    if (done % 10000 == 0L) {
                                        logInfo(String.format(format, done.toDouble() * 100 / total))
                                    }
                                    if (done % 100000 == 0L && !txn.flush()) {
                                        throw IllegalStateException("cannot flush")
                                    }
                                }
                                idSet.clear()
                                return done
                            }
                        }
                )
            }
        }
    }

    fun refactorDropEmptyPrimaryLinkTables() {
        store.executeInReadonlyTransaction { tx ->
            val txn = tx as PersistentStoreTransaction
            for (entityType in store.getEntityTypes(txn)) {
                runReadonlyTransactionSafeForEntityType(entityType, Runnable {
                    val envTxn = txn.environmentTransaction
                    val entityTypeId = store.getEntityTypeId(txn, entityType, false)
                    val linksTable = store.getLinksTable(txn, entityTypeId)
                    val primaryCount = linksTable.getPrimaryCount(envTxn)
                    if (primaryCount != 0L && linksTable.getSecondaryCount(envTxn) == 0L) {
                        store.environment.executeInTransaction { txn -> linksTable.truncateFirst(txn) }
                        logInfo("Drop links' tables when primary index is empty for [$entityType]")
                    }
                })
            }
        }
    }

    fun refactorMakeLinkTablesConsistent(internalSettings: Store?) {
        store.executeInReadonlyTransaction { tx ->
            val txn = tx as PersistentStoreTransaction
            for (entityType in store.getEntityTypes(txn)) {
                logInfo("Refactoring making links' tables consistent for [$entityType]")
                runReadonlyTransactionSafeForEntityType(entityType, Runnable {
                    val redundantLinks: MutableCollection<Pair<ByteIterable, ByteIterable>> = ArrayList()
                    val deleteLinks: MutableCollection<Pair<ByteIterable, ByteIterable>> = ArrayList()
                    val entityTypeId = store.getEntityTypeId(txn, entityType, false)
                    val linksTable = store.getLinksTable(txn, entityTypeId)
                    val envTxn = txn.environmentTransaction
                    val all: LongSet = PackedLongHashSet()
                    val linkFilter: LongSet = PackedLongHashSet()
                    store.getEntitiesIndexCursor(txn, entityTypeId).use { cursor ->
                        while (cursor.next) {
                            all.add(LongBinding.compressedEntryToLong(cursor.key))
                        }
                    }
                    val redundantLinkTypes = IntHashSet()
                    val deletedLinkTypes = IntHashSet()
                    val deletedLinkIds = IntHashSet()
                    linksTable.getFirstIndexCursor(envTxn).use { cursor ->
                        while (cursor.next) {
                            val first = cursor.key
                            val second = cursor.value
                            var linkValue: LinkValue? = null
                            val localId = LongBinding.compressedEntryToLong(first)
                            if (!all.contains(localId)) {
                                try {
                                    linkValue = LinkValue.entryToLinkValue(second)
                                    deletedLinkTypes.add(linkValue.entityId.typeId)
                                    deletedLinkIds.add(linkValue.linkId)
                                } catch (ignore: ArrayIndexOutOfBoundsException) {
                                }
                                do {
                                    deleteLinks.add(Pair(first, second))
                                } while (cursor.nextDup)
                                continue
                            } else {
                                linkFilter.add((first.hashCode().toLong() shl 31) + second.hashCode().toLong())
                            }
                            if (linkValue == null) {
                                try {
                                    linkValue = LinkValue.entryToLinkValue(second)
                                } catch (ignore: ArrayIndexOutOfBoundsException) {
                                    deleteLinks.add(Pair(first, second))
                                }
                            }
                            if (linkValue != null) {
                                val targetEntityId = linkValue.entityId
                                // if target doesn't exist
                                if (store.getLastVersion(txn, targetEntityId) < 0) {
                                    deletedLinkTypes.add(targetEntityId.typeId)
                                    deletedLinkIds.add(linkValue.linkId)
                                    deleteLinks.add(Pair(first, second))
                                    continue
                                } else {
                                    linkFilter.add((first.hashCode().toLong() shl 31) + second.hashCode().toLong())
                                }
                                if (!linksTable.contains2(envTxn, first, second)) {
                                    redundantLinkTypes.add(targetEntityId.typeId)
                                    redundantLinks.add(Pair(first, second))
                                }
                            }
                        }
                    }
                    if (!redundantLinks.isEmpty()) {
                        store.environment.executeInTransaction { txn ->
                            for (badLink in redundantLinks) {
                                linksTable.put(txn, badLink.first, badLink.second)
                            }
                        }
                        logInfo(redundantLinks.size.toString() + " missing links found for [" + entityType + ']')
                        redundantLinks.clear()
                    }
                    linksTable.getSecondIndexCursor(envTxn).use { cursor ->
                        while (cursor.next) {
                            val second = cursor.key
                            val first = cursor.value
                            if (!linkFilter.contains((first.hashCode().toLong() shl 31) + second.hashCode().toLong())) {
                                if (!linksTable.contains(envTxn, first, second)) {
                                    redundantLinks.add(Pair(first, second))
                                }
                            }
                        }
                    }
                    val redundantLinksSize = redundantLinks.size
                    val deleteLinksSize = deleteLinks.size
                    if (redundantLinksSize > 0 || deleteLinksSize > 0) {
                        store.environment.executeInTransaction { txn ->
                            for (redundantLink in redundantLinks) {
                                deletePair(linksTable.getSecondIndexCursor(txn), redundantLink.first, redundantLink.second)
                            }
                            for (deleteLink in deleteLinks) {
                                deletePair(linksTable.getFirstIndexCursor(txn), deleteLink.first, deleteLink.second)
                                deletePair(linksTable.getSecondIndexCursor(txn), deleteLink.second, deleteLink.first)
                            }
                        }
                        if (logger.isInfoEnabled) {
                            if (redundantLinksSize > 0) {
                                val redundantLinkTypeNames = ArrayList<String>(redundantLinkTypes.size)
                                for (typeId in redundantLinkTypes) {
                                    redundantLinkTypeNames.add(store.getEntityType(txn, typeId))
                                }
                                logInfo("$redundantLinksSize redundant links found and fixed for [$entityType] and targets: $redundantLinkTypeNames")
                            }
                            if (deleteLinksSize > 0) {
                                val deletedLinkTypeNames = ArrayList<String>(deletedLinkTypes.size)
                                for (typeId in deletedLinkTypes) {
                                    try {
                                        val entityTypeName = store.getEntityType(txn, typeId)
                                        deletedLinkTypeNames.add(entityTypeName)
                                    } catch (t: Throwable) {
                                        // ignore
                                    }
                                }
                                val deletedLinkIdsNames = ArrayList<String>(deletedLinkIds.size)
                                for (typeId in deletedLinkIds) {
                                    try {
                                        val linkName = store.getLinkName(txn, typeId)
                                        linkName?.let { deletedLinkIdsNames.add(linkName) }
                                    } catch (t: Throwable) {
                                        // ignore
                                    }
                                }
                                logInfo("$deleteLinksSize phantom links found and fixed for [$entityType] and targets: $deletedLinkTypeNames")
                                logInfo("Link types: $deletedLinkIdsNames")
                            }
                        }
                    }
                    Settings.delete(internalSettings!!, "Link null-indices present") // reset link null indices
                })
            }
        }
    }

    fun refactorMakePropTablesConsistent() {
        store.executeInReadonlyTransaction { tx ->
            val txn = tx as PersistentStoreTransaction
            for (entityType in store.getEntityTypes(txn)) {
                logInfo("Refactoring making props' tables consistent for [$entityType]")
                runReadonlyTransactionSafeForEntityType(entityType, Runnable {
                    val entityTypeId = store.getEntityTypeId(txn, entityType, false)
                    val propTable = store.getPropertiesTable(txn, entityTypeId)
                    val envTxn = txn.environmentTransaction
                    val props = IntHashMap<LongHashMap<PropertyValue>>()
                    val all: LongSet = PackedLongHashSet()
                    store.getEntitiesIndexCursor(txn, entityTypeId).use { cursor ->
                        while (cursor.next) {
                            all.add(LongBinding.compressedEntryToLong(cursor.key))
                        }
                    }
                    val propertyTypes = store.propertyTypes
                    val entitiesToDelete: LongSet = LongHashSet()
                    store.getPrimaryPropertyIndexCursor(txn, propTable).use { cursor ->
                        while (cursor.next) {
                            val propKey = PropertyKey.entryToPropertyKey(cursor.key)
                            val localId = propKey.entityLocalId
                            if (!all.contains(localId)) {
                                entitiesToDelete.add(localId)
                                continue
                            }
                            val propValue = propertyTypes.entryToPropertyValue(cursor.value)
                            val propId = propKey.propertyId
                            var entitiesToValues = props[propId]
                            if (entitiesToValues == null) {
                                entitiesToValues = LongHashMap()
                                props[propId] = entitiesToValues
                            }
                            entitiesToValues[localId] = propValue
                        }
                    }
                    if (!entitiesToDelete.isEmpty()) {
                        store.executeInTransaction { tx ->
                            val txn = tx as PersistentStoreTransaction
                            for (localId in entitiesToDelete) {
                                store.deleteEntity(txn, PersistentEntity(store, PersistentEntityId(entityTypeId, localId!!)))
                            }
                        }
                    }
                    val missingPairs: MutableList<Pair<Int, Pair<ByteIterable, ByteIterable>>> = ArrayList()
                    val allPropsMap = IntHashMap<MutableSet<Long>>()
                    for (propId in props.keys) {
                        val valueIndex = propTable.getValueIndex(txn, propId, false)
                        val valueCursor = valueIndex?.openCursor(envTxn)
                        val entitiesToValues = props[propId]
                        val localIdSet: Set<Long> = entitiesToValues.keys
                        val sortedLocalIdSet = TreeSet(localIdSet)
                        allPropsMap[propId] = sortedLocalIdSet
                        val localIds = sortedLocalIdSet.toTypedArray()
                        for (localId in localIds) {
                            val propValue = entitiesToValues[localId]
                            for (secondaryKey in PropertiesTable.createSecondaryKeys(
                                    propertyTypes, PropertyTypes.propertyValueToEntry(propValue), propValue.type)) {
                                val secondaryValue: ByteIterable = LongBinding.longToCompressedEntry(localId)
                                if (valueCursor == null || !valueCursor.getSearchBoth(secondaryKey, secondaryValue)) {
                                    missingPairs.add(Pair(propId, Pair(secondaryKey, secondaryValue)))
                                }
                            }
                        }
                        valueCursor?.close()
                    }
                    if (missingPairs.isNotEmpty()) {
                        store.executeInTransaction { tx ->
                            val txn = tx as PersistentStoreTransaction
                            for (pair in missingPairs) {
                                val valueIndex = propTable.getValueIndex(txn, pair.first, true)
                                val missing = pair.second
                                if (valueIndex == null) {
                                    throw NullPointerException("Can't be")
                                }
                                valueIndex.put(txn.environmentTransaction, missing.first, missing.second)
                            }
                        }
                        logInfo("${missingPairs.size} missing secondary keys found and fixed for [$entityType]")
                    }
                    val phantomPairs: MutableList<Pair<Int, Pair<ByteIterable, ByteIterable>>> = ArrayList()
                    for ((propId, value1) in propTable.valueIndices) {
                        val entitiesToValues = props[propId]
                        val c = value1.openCursor(envTxn)
                        while (c.next) {
                            val keyEntry = c.key
                            val valueEntry = c.value
                            val propValue = entitiesToValues[LongBinding.compressedEntryToLong(valueEntry)]
                            if (propValue != null) {
                                val data = propValue.data
                                val typeId = propValue.type.typeId
                                val dataClass: Class<out Comparable<Any>>?
                                val objectBinding: ComparableBinding
                                if (typeId == ComparableValueType.COMPARABLE_SET_VALUE_TYPE) {
                                    @Suppress("UNCHECKED_CAST")
                                    dataClass = (data as ComparableSet<Comparable<Any>>).itemClass
                                    if (dataClass == null) {
                                        phantomPairs.add(Pair(propId, Pair(keyEntry, valueEntry)))
                                        continue
                                    }
                                    objectBinding = propertyTypes.getPropertyType(dataClass).binding
                                } else {
                                    dataClass = data.javaClass
                                    objectBinding = propValue.binding
                                }
                                try {
                                    val value = objectBinding.entryToObject(keyEntry)
                                    if (dataClass == value.javaClass) {
                                        if (typeId == ComparableValueType.COMPARABLE_SET_VALUE_TYPE) {
                                            if ((data as ComparableSet<*>).containsItem(value)) {
                                                continue
                                            }
                                        } else if (PropertyTypes.toLowerCase(data).compareTo(value) == 0) {
                                            continue
                                        }
                                    }
                                } catch (t: Throwable) {
                                    logger.error("Error reading property value index ", t)
                                    throwJVMError(t)
                                }
                            }
                            phantomPairs.add(Pair(propId, Pair(keyEntry, valueEntry)))
                        }
                        c.close()
                    }
                    if (phantomPairs.isNotEmpty()) {
                        store.executeInTransaction { tx ->
                            val txn = tx as PersistentStoreTransaction
                            val envTxn = txn.environmentTransaction
                            for (pair in phantomPairs) {
                                val valueIndex = propTable.getValueIndex(txn, pair.first, true)
                                val phantom = pair.second
                                if (valueIndex == null) {
                                    throw NullPointerException("Can't be")
                                }
                                deletePair(valueIndex.openCursor(envTxn), phantom.first, phantom.second)
                            }
                        }
                        logInfo("${phantomPairs.size} phantom secondary keys found and fixed for [$entityType]")
                    }
                    val phantomIds: MutableList<Pair<Int, Long>> = ArrayList()
                    val c = propTable.allPropsIndex.openCursor(envTxn)
                    while (c.next) {
                        val propId = IntegerBinding.compressedEntryToInt(c.key)
                        val localId = LongBinding.compressedEntryToLong(c.value)
                        val localIds = allPropsMap[propId]
                        if (localIds == null || !localIds.remove(localId)) {
                            phantomIds.add(Pair(propId, localId))
                        } else {
                            if (localIds.isEmpty()) {
                                allPropsMap.remove(propId)
                            }
                        }
                    }
                    c.close()
                    if (!allPropsMap.isEmpty()) {
                        val added = store.computeInTransaction { txn ->
                            var count = 0
                            val allPropsIndex = propTable.allPropsIndex
                            val envTxn = (txn as PersistentStoreTransaction).environmentTransaction
                            for ((key, value) in allPropsMap) {
                                val keyEntry = IntegerBinding.intToCompressedEntry(key!!)
                                for (localId in value) {
                                    allPropsIndex.put(envTxn, keyEntry, LongBinding.longToCompressedEntry(localId))
                                    ++count
                                }
                            }
                            count
                        }
                        logInfo("$added missing id pairs found and fixed for [$entityType]")
                    }
                    if (phantomIds.isNotEmpty()) {
                        store.executeInTransaction { txn ->
                            val allPropsIndex = propTable.allPropsIndex
                            val envTxn = (txn as PersistentStoreTransaction).environmentTransaction
                            allPropsIndex.openCursor(envTxn).use { c ->
                                for (phantom in phantomIds) {
                                    if (c.getSearchBoth(IntegerBinding.intToCompressedEntry(phantom.first), LongBinding.longToCompressedEntry(phantom.second))) {
                                        c.deleteCurrent()
                                    }
                                }
                            }
                        }
                        logInfo("${phantomIds.size} phantom id pairs found and fixed for [$entityType]")
                    }
                })
            }
        }
    }

    fun refactorFixNegativeFloatAndDoubleProps(settings: Store) {
        store.executeInReadonlyTransaction() { tx ->
            for (entityType in store.getEntityTypes(tx as PersistentStoreTransaction).toList()) {
                store.executeInTransaction { t ->
                    val txn = t as PersistentStoreTransaction
                    if (Settings.get(txn.environmentTransaction, settings, "refactorFixNegativeFloatAndDoubleProps($entityType) applied") == "y") {
                        return@executeInTransaction
                    }
                    logInfo("Refactoring fixing negative float & double props for [$entityType]")
                    val entityTypeId = store.getEntityTypeId(txn, entityType, false)
                    val propTable = store.getPropertiesTable(txn, entityTypeId)
                    val props = HashMap<PropertyKey, Pair<PropertyValue, ByteIterable>>()
                    val propertyTypes = store.propertyTypes
                    store.getPrimaryPropertyIndexCursor(txn, propTable).use { cursor ->
                        while (cursor.next) {
                            try {
                                ArrayByteIterable(cursor.value).let {
                                    val propertyType = propertyTypes.getPropertyType((it.iterator().next() xor (0x80).toByte()).toInt())
                                    when (propertyType.typeId) {
                                        ComparableValueType.FLOAT_VALUE_TYPE -> {
                                            props[PropertyKey.entryToPropertyKey(cursor.key)] = propertyTypes.entryToPropertyValue(it, FloatBinding.BINDING) to it
                                        }
                                        ComparableValueType.DOUBLE_VALUE_TYPE -> {
                                            props[PropertyKey.entryToPropertyKey(cursor.key)] = propertyTypes.entryToPropertyValue(it, DoubleBinding.BINDING) to it
                                        }
                                        else -> {
                                        }
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    if (props.isNotEmpty()) {
                        props.keys.sortedBy { it.entityLocalId }.forEach { key ->
                            props[key]?.let { (propValue, it) ->
                                propTable.put(txn, key.entityLocalId,
                                        PropertyTypes.propertyValueToEntry(propValue),
                                        it, key.propertyId, propValue.type)
                            }
                        }
                        logInfo("${props.size} negative float & double props fixed.")
                    }
                    Settings.set(txn.environmentTransaction, settings, "refactorFixNegativeFloatAndDoubleProps($entityType) applied", "y")
                }
            }
        }
    }

    fun refactorRemoveHistoryStores() {
        val environment = store.environment
        environment.executeInReadonlyTransaction { txn ->
            val persistentStoreName = store.name
            for (storeName in environment.getAllStoreNames(txn)) {
                if (storeName.startsWith(persistentStoreName) && storeName.endsWith("#history")) {
                    environment.executeInTransaction { txn -> environment.removeStore(storeName, txn) }
                }
            }
        }
    }

    private fun safeExecuteRefactoringForEntityType(entityType: String,
                                                    executable: StoreTransactionalExecutable) {
        try {
            store.executeInTransaction(executable)
        } catch (t: Throwable) {
            logger.error("Failed to execute refactoring for entity type: $entityType", t)
            throwJVMError(t)
        }
    }

    private fun safeExclusiveExecuteRefactoringForEntityType(entityType: String,
                                                             executable: StoreTransactionalExecutable) {
        try {
            store.executeInTransaction(executable)
        } catch (t: Throwable) {
            logger.error("Failed to execute refactoring for entity type: $entityType", t)
            throwJVMError(t)
        }
    }

    private fun transactionalCopyAndRemoveEntitiesStore(sourceName: String, targetName: String) {
        val env = store.environment
        env.executeInTransaction { txn ->
            val store = env.openStore(sourceName, StoreConfig.USE_EXISTING, txn)
            val storeCopy = env.openStore(targetName, StoreConfig.WITHOUT_DUPLICATES_WITH_PREFIXING, txn)
            val cursor = store.openCursor(txn)
            var lastKey: ArrayByteIterable? = null
            while (cursor.next) {
                val key = ArrayByteIterable(cursor.key)
                if (lastKey != null && lastKey >= key) {
                    throw IllegalStateException("Invalid key order")
                }
                storeCopy.putRight(txn, key, ArrayByteIterable(cursor.value))
                lastKey = key
            }
            cursor.close()
            env.removeStore(sourceName, txn)
        }
    }

    companion object : KLogging() {

        private fun runReadonlyTransactionSafeForEntityType(entityType: String,
                                                            runnable: Runnable) {
            try {
                runnable.run()
            } catch (ignore: ReadonlyTransactionException) {
                // that fixes XD-377, XD-492 and similar not reported issues
            } catch (t: Throwable) {
                logger.error("Failed to execute refactoring for entity type: $entityType", t)
                throwJVMError(t)
            }
        }

        private fun deletePair(c: Cursor, key: ByteIterable, value: ByteIterable) {
            if (c.getSearchBoth(key, value)) {
                c.deleteCurrent()
            }
            c.close()
        }

        private fun throwJVMError(t: Throwable) {
            if (t is VirtualMachineError) {
                throw EntityStoreException(t)
            }
        }

        private fun logInfo(message: String) {
            if (logger.isInfoEnabled) {
                logger.info(message)
            }
        }
    }
}
