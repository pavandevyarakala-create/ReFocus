package com.reeltrak.app.core.sync

import android.util.Log
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.ktx.Firebase
import com.reeltrak.app.data.local.dao.DailyReelDataDao
import com.reeltrak.app.data.local.entity.DailyReelData
import com.reeltrak.app.domain.repository.PreferencesRepository
import com.reeltrak.app.data.remote.dto.CloudDailyReelData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import dagger.Lazy
import com.reeltrak.app.services.tracking.DayManager
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManager @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val dailyReelDataDao: DailyReelDataDao,
    private val preferencesRepository: PreferencesRepository,
    private val dayManagerLazy: Lazy<DayManager>
) {
    suspend fun backupToCloud(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uid = Firebase.auth.currentUser?.uid
            if (uid == null) {
                Log.e("FirestoreSync", "Cannot backup to cloud: User is not logged in.")
                return@withContext Result.failure(Exception("User not logged in"))
            }
            Log.d("SyncManager", "Starting cloud backup for user: $uid")
            ensureUserEmailSaved(uid)
            
            // Backup Daily Data -> daily_data/{YYYY-MM-DD}
            val allDailyData = dailyReelDataDao.getAllDailyDataSync()
            allDailyData.forEach { entity ->
                val dto = CloudDailyReelData(
                    dateString = entity.dateString,
                    totalReelsCount = entity.totalReelsCount,
                    dailyGoal = entity.dailyGoal,
                    isStrictModeOn = entity.isStrictModeOn
                )
                val ref = firestore.collection("users").document(uid).collection("daily_data").document(dto.dateString)
                Log.d("FirestoreSync", "Writing to path: ${ref.path}")
                ref.set(dto, SetOptions.merge())
                    .addOnSuccessListener { Log.d("FirestoreSync", "SUCCESS: ${ref.path}") }
                    .addOnFailureListener { e -> Log.e("FirestoreSync", "ERROR writing ${ref.path}: ${e.localizedMessage}") }
                    .await()
            }


            Log.d("FirestoreSync", "Cloud backup fully successful for user: $uid")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirestoreSync", "Cloud backup failed", e)
            Result.failure(e)
        }
    }

    suspend fun backupToday(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uid = Firebase.auth.currentUser?.uid
            if (uid == null) {
                Log.e("FirestoreSync", "Cannot backup to cloud: User is not logged in.")
                return@withContext Result.failure(Exception("User not logged in"))
            }
            Log.d("SyncManager", "Starting targeted backup for today for user: $uid")
            ensureUserEmailSaved(uid)

            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val entity = dailyReelDataDao.getDailyDataSync(todayStr)

            if (entity != null) {
                val dto = CloudDailyReelData(
                    dateString = entity.dateString,
                    totalReelsCount = entity.totalReelsCount,
                    dailyGoal = entity.dailyGoal,
                    isStrictModeOn = entity.isStrictModeOn
                )
                val ref = firestore.collection("users").document(uid).collection("daily_data").document(dto.dateString)
                ref.set(dto, SetOptions.merge()).await()
            }

            Log.d("FirestoreSync", "Targeted today backup successful for user: $uid")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirestoreSync", "Targeted today backup failed", e)
            Result.failure(e)
        }
    }

    suspend fun backupSpecificDay(dateStr: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uid = Firebase.auth.currentUser?.uid
            if (uid == null) {
                Log.e("FirestoreSync", "Cannot backup to cloud: User is not logged in.")
                return@withContext Result.failure(Exception("User not logged in"))
            }
            Log.d("SyncManager", "Starting targeted backup for $dateStr for user: $uid")
            ensureUserEmailSaved(uid)

            val entity = dailyReelDataDao.getDailyDataSync(dateStr)

            if (entity != null) {
                val dto = CloudDailyReelData(
                    dateString = entity.dateString,
                    totalReelsCount = entity.totalReelsCount,
                    dailyGoal = entity.dailyGoal,
                    isStrictModeOn = entity.isStrictModeOn
                )
                val ref = firestore.collection("users").document(uid).collection("daily_data").document(dto.dateString)
                ref.set(dto, SetOptions.merge()).await()
            }


            Log.d("FirestoreSync", "Targeted backup successful for $dateStr for user: $uid")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirestoreSync", "Targeted backup failed for $dateStr", e)
            Result.failure(e)
        }
    }

    suspend fun restoreFromCloud(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val uid = Firebase.auth.currentUser?.uid
            if (uid == null) {
                Log.e("FirestoreSync", "Cannot restore from cloud: User is not logged in.")
                return@withContext Result.failure(Exception("User not logged in"))
            }
            Log.d("SyncManager", "Starting cloud restore for user: $uid")
            ensureUserEmailSaved(uid)
            
            // UPWARD SYNC FIX: Push all local data to cloud BEFORE we wipe the historical local database.
            // This safely syncs any data accumulated while offline or logged out.
            backupToCloud()
            
            dayManagerLazy.get().setRestoring(true)

            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val localTodayData = dailyReelDataDao.getDailyDataSync(todayStr)

            // Restore Daily Data
            val dailyDataSnapshot = firestore.collection("users").document(uid).collection("daily_data").get().await()
            
            val restoredList = dailyDataSnapshot.documents.mapNotNull { doc ->
                val dto = doc.toObject(CloudDailyReelData::class.java)
                if (dto != null) {
                    // The golden rule: ALWAYS ignore today's record from the cloud.
                    // Today's data is strictly controlled locally.
                    if (dto.dateString == todayStr) {
                        // DOWNWARD SYNC EXCEPTION: If local today's count is > 0, strict ignore. 
                        // If local count == 0 (e.g. brand new install or unused day), allow the cloud to restore today's progress.
                        if (localTodayData != null && localTodayData.totalReelsCount > 0) {
                            return@mapNotNull null
                        }
                    }
                    
                    DailyReelData(
                        dateString = dto.dateString,
                        totalReelsCount = dto.totalReelsCount,
                        dailyGoal = dto.dailyGoal,
                        isStrictModeOn = dto.isStrictModeOn,
                        totalTimeSpentMillis = 0L, // Local only default
                        sessions = emptyList(),    // Local only default
                        updatedAt = System.currentTimeMillis()
                    )
                } else null
            }
            
            // Transaction: Wipe historical and Replace
            if (restoredList.isNotEmpty()) {
                dailyReelDataDao.clearHistoricalDataExcept(todayStr)
                dailyReelDataDao.insertAll(restoredList)
            } else {
                Log.d("FirestoreSync", "Server payload empty. Preserving local historical data.")
            }


            Log.d("FirestoreSync", "Cloud restore successful for user: $uid")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirestoreSync", "Cloud restore failed", e)
            Result.failure(e)
        } finally {
            dayManagerLazy.get().setRestoring(false)
        }
    }

    private suspend fun ensureUserEmailSaved(uid: String) {
        try {
            val email = Firebase.auth.currentUser?.email
            if (email != null) {
                firestore.collection("users").document(uid)
                    .set(mapOf("email" to email), SetOptions.merge())
                    .await()
            }
        } catch (e: Exception) {
            Log.e("FirestoreSync", "Failed to save user email", e)
        }
    }

    suspend fun performLegacyMigration(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val hasMigrated = preferencesRepository.hasMigratedToServer.first()
            if (hasMigrated) {
                Log.d("SyncManager", "Legacy migration already completed. Skipping.")
                return@withContext Result.success(Unit)
            }

            val uid = Firebase.auth.currentUser?.uid
            if (uid == null) {
                Log.e("FirestoreSync", "Cannot perform legacy migration: User is not logged in.")
                return@withContext Result.failure(Exception("User not logged in"))
            }
            Log.d("SyncManager", "Starting LEGACY MIGRATION (Smart Merge) for user: $uid")
            ensureUserEmailSaved(uid)

            val todayStr = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            
            // 1. Fetch server data
            val dailyDataSnapshot = firestore.collection("users").document(uid).collection("daily_data").get().await()
            val serverDataMap = dailyDataSnapshot.documents.mapNotNull { it.toObject(CloudDailyReelData::class.java) }.associateBy { it.dateString }
            
            // 2. Fetch local data
            val localDataList = dailyReelDataDao.getAllDailyDataSync()
            val localDataMap = localDataList.associateBy { it.dateString }
            
            // 3. Perform Smart Merge
            val mergedDataToPush = mutableListOf<DailyReelData>()
            val mergedDataToSaveLocally = mutableListOf<DailyReelData>()
            
            val allDates = (serverDataMap.keys + localDataMap.keys).distinct()
            
            for (date in allDates) {
                val serverRecord = serverDataMap[date]
                val localRecord = localDataMap[date]
                
                if (date == todayStr) {
                    // Exception for "Today": Local always wins (unless completely missing, which is impossible with DayManager, but just in case)
                    if (localRecord != null) {
                        mergedDataToPush.add(localRecord)
                    } else if (serverRecord != null) {
                        val newLocal = DailyReelData(
                            dateString = serverRecord.dateString,
                            totalReelsCount = serverRecord.totalReelsCount,
                            dailyGoal = serverRecord.dailyGoal,
                            isStrictModeOn = serverRecord.isStrictModeOn,
                            totalTimeSpentMillis = 0L,
                            sessions = emptyList(),
                            updatedAt = System.currentTimeMillis()
                        )
                        mergedDataToSaveLocally.add(newLocal)
                        mergedDataToPush.add(newLocal)
                    }
                } else {
                    // Historical dates
                    if (localRecord != null && serverRecord == null) {
                        // Exists only locally -> push to server
                        mergedDataToPush.add(localRecord)
                    } else if (localRecord == null && serverRecord != null) {
                        // Exists only on server -> save locally
                        val newLocal = DailyReelData(
                            dateString = serverRecord.dateString,
                            totalReelsCount = serverRecord.totalReelsCount,
                            dailyGoal = serverRecord.dailyGoal,
                            isStrictModeOn = serverRecord.isStrictModeOn,
                            totalTimeSpentMillis = 0L,
                            sessions = emptyList(),
                            updatedAt = System.currentTimeMillis()
                        )
                        mergedDataToSaveLocally.add(newLocal)
                    } else if (localRecord != null && serverRecord != null) {
                        // Conflict: Higher reelCount wins
                        if (localRecord.totalReelsCount >= serverRecord.totalReelsCount) {
                            // Local wins, push to server
                            mergedDataToPush.add(localRecord)
                        } else {
                            // Server wins, update local
                            val updatedLocal = localRecord.copy(
                                totalReelsCount = serverRecord.totalReelsCount,
                                dailyGoal = serverRecord.dailyGoal,
                                isStrictModeOn = serverRecord.isStrictModeOn,
                                updatedAt = System.currentTimeMillis()
                            )
                            mergedDataToSaveLocally.add(updatedLocal)
                        }
                    }
                }
            }
            
            // 4. Save losing server records locally
            if (mergedDataToSaveLocally.isNotEmpty()) {
                dailyReelDataDao.insertAll(mergedDataToSaveLocally)
            }
            
            // 5. Push winning local records to server
            for (entity in mergedDataToPush) {
                val dto = CloudDailyReelData(
                    dateString = entity.dateString,
                    totalReelsCount = entity.totalReelsCount,
                    dailyGoal = entity.dailyGoal,
                    isStrictModeOn = entity.isStrictModeOn
                )
                firestore.collection("users").document(uid).collection("daily_data").document(dto.dateString)
                    .set(dto, SetOptions.merge()).await()
            }
            
            // 6. Mark migration complete
            preferencesRepository.setHasMigratedToServer(true)
            Log.d("FirestoreSync", "Legacy migration (Smart Merge) completed successfully.")
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("FirestoreSync", "Legacy migration failed", e)
            Result.failure(e)
        }
    }
}
