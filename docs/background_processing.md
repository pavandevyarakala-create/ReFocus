# Background Processing

The success of REEL hinges on its ability to run invisibly and efficiently in the background without draining the device's battery or dropping foreground frames.

## 1. Coroutine Scopes and Thread Ownership

### `ReelTrackerService`
- **Scope**: `serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`
- **Purpose**: Master lifecycle owner for all background processing. Uses a `SupervisorJob` so that if one event processing coroutine crashes or throws an exception, it does not bring down the entire accessibility service.
- **Cancellation**: `serviceScope.cancel()` is called strictly in `onDestroy()`.

### `EventProcessor`
- **Threading**: Dispatches debouncing timers using `Dispatchers.Default` (CPU bound).
- **Queues**: Uses `MutableSharedFlow` to act as a buffer. 

### `FloatingCounterManager`
- **Threading**: Android requires `WindowManager.addView()` and all Compose UI manipulation to occur on the **Main Thread**. 
- **Dispatching**: `ReelTrackerService` explicitly calls `serviceScope.launch(Dispatchers.Main)` when attaching, updating, or destroying the floating overlay.

## 2. Timers and Delays

- **Idle Timeout**: When the user stops scrolling but remains in the app, a coroutine timer is launched: `delay(2000L)`. If no new reels are detected within 2 seconds, the session transitions to `EXITING` and then `IDLE`.
- **Cancellation**: If a new reel is detected *during* the timeout period, the `hideJob` is cancelled, seamlessly resuming the active session without breaking the session block in the database.

## 3. BroadcastReceivers

- **Debug Harness**: The service registers an internal `BroadcastReceiver` during development (`ACTION_DEBUG_SHOW_OVERLAY`, etc.) to mock lifecycle events and force UI states without actually opening Instagram.

## 4. WorkManager vs AccessibilityService

- **Why no WorkManager?**: WorkManager is designed for deferrable, guaranteed background work (e.g., syncing a database once a day). REEL requires real-time, millisecond-accurate interception of screen events, which can only be achieved via a persistently bound `AccessibilityService`. No `Worker` classes are used in this project.
