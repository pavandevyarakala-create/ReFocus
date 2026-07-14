# Local Database Architecture

REEL uses Android's **Room persistence library** for all historical analytics data. The database is entirely local; no data ever leaves the device.

## 1. Database Configuration

- **Class**: `ReelTrakDatabase` extends `RoomDatabase`.
- **Version**: Currently v1.
- **Initialization**: Provided as a Singleton via `DatabaseModule.kt` (Dagger Hilt).

## 2. Entities

### `SessionEntity`
Represents a single uninterrupted sequence of scrolling short-form video.
- **Table Name**: `scroll_sessions`
- **Fields**:
  - `id` (PrimaryKey, AutoGenerate)
  - `appName` (String): e.g., "com.instagram.android"
  - `startTimeStamp` (Long): Epoch MS when detection started.
  - `endTimeStamp` (Long): Epoch MS when session ended (exited app or stopped scrolling).
  - `reelsScrolled` (Int): Total reels consumed.

### `DailyStatisticsEntity`
Represents an aggregated cache of total reels scrolled on a specific day, used for fast lookup of "Today's Limit".
- **Table Name**: `daily_statistics`
- **Fields**:
  - `dateTimestamp` (Long, PrimaryKey): Set strictly to the Epoch MS of `00:00:00` for that day.
  - `totalReelsScrolled` (Int): Counter for the day.
  - `lastUpdatedTimestamp` (Long): Audit field.

## 3. Data Access Objects (DAOs)

### `SessionDao`
- **`insertSession(session: SessionEntity)`**: Called by `ReelTrackerService` when a session concludes (transitions to IDLE or DESTROYED). Uses `REPLACE` conflict strategy.
- **`getAllSessions(): Flow<List<SessionEntity>>`**: Core flow driving the Dashboard. Returns all sessions ordered by `startTimeStamp DESC`.
  - *Optimization Note*: This query loads the entire history into RAM. In `performance_baseline.md`, it is recommended to implement pagination or `LIMIT` clauses as the dataset grows over months of usage.

### `DailyStatisticsDao`
- **`insert(dailyStat: DailyStatisticsEntity)`**: Initializes the day.
- **`incrementCount(date: Long, amount: Int, timestamp: Long)`**: Efficient SQL `UPDATE` statement that performs `totalReelsScrolled = totalReelsScrolled + :amount`. This prevents race conditions since the Service directly updates the value atomically in SQL.
- **`getStatisticsForDate(date: Long): DailyStatisticsEntity?`**: Synchronous read used by the Service to check limits.
- **`getStatisticsForDateFlow(date: Long): Flow<DailyStatisticsEntity?>`**: Used by `DashboardViewModel` to display today's progress.

## 4. Data Lifecycle & Expected Growth

- **Read/Write Paths**: `ReelTrackerService` strictly handles WRITES. `DashboardViewModel` strictly handles READS.
- **Data Growth**: A heavy user might generate ~20 sessions a day.
  - 1 year = ~7,300 rows in `SessionEntity`.
  - Room handles datasets of this size trivially (< 5MB on disk), but the in-memory `.map` transformations in the ViewModel must be offloaded to `Dispatchers.Default`.
- **Migrations**: No migrations currently exist. Future schema changes require an `AutoMigration` array in `ReelTrakDatabase`.
