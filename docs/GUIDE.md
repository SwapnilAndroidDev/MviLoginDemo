# MVI Login → Home list → Detail demo: step-by-step guide and interview notes

Jetpack Compose · MVI · Coroutines · Flow / StateFlow / SharedFlow / Channel · Scopes · enum / sealed · Dagger (Hilt) · zip / merge / combine / flatMapLatest · Retrofit + OkHttp + kotlinx.serialization · type-safe Navigation · pagination · Coil

Each section says **what** the code does, **why** it uses that tool, **why not** the alternative, and **how to optimise it**. Sections 1–9 cover the login screen and the core concepts. **Part 2 (sections 10–16)** covers the Home list, the Detail page and networking.

---

## 0. Run it

1. Open `MviLoginDemo/` in Android Studio (Ladybug or newer), let Gradle sync, and run `app`. A device or emulator with internet is needed: the app talks to the live **DummyJSON** API (`https://dummyjson.com`), a free, open-source fake REST API.
2. On the login screen, tap **Use demo account** (or type `emilys` / `emilyspass`), then **Sign in**.

| What you do | What happens | Concepts |
|---|---|---|
| Sign in with the demo account | `POST /auth/login` → (`/auth/me` **zip** `/carts/user/1`) → Home | suspend, zip, retry, Channel effect |
| Wrong password | "Incorrect username or password" | HTTP 400 → sealed `AppError.InvalidCredentials` |
| Airplane mode on the login screen | Offline banner, button disabled | callbackFlow → stateIn → merge → combine |
| Type in the Home search box | Debounced search, stale requests cancelled | debounce + combine + **flatMapLatest** |
| Tap a category chip | Products from that category | combine + flatMapLatest |
| Scroll to the bottom | Next 20 products are appended | derivedStateOf + snapshotFlow + pagination |
| Tap a product | Detail: image pager, price, stock, warranty, reviews | type-safe nav args, `stateIn`, sealed UI state |
| Tap **Expire** on Home | Back to login with "session expired" | SharedFlow broadcast (the same path a real 401 takes) |
| Tap **Logout** | Back to login, back stack cleared | navigation `popUpTo` |

3. Run the unit tests (`./gradlew test`). They use fakes, not the network, plus virtual time. For example, they assert that zip finishes in 2000 ms instead of 2600 ms, and that flatMapLatest drops a stale category response.

---

## 1. The MVI loop

```
           ┌──────────────────────────── UI (Compose) ────────────────────────────┐
           │  LoginScreen(state, onIntent)   ◄── collectAsStateWithLifecycle()    │
           │        │ user types/taps              ◄── effects (navigate/snackbar) │
           └────────┼─────────────────────────────────────────▲───────────▲──────┘
                    ▼ LoginIntent                              │ StateFlow │ Channel
           ┌──────────────────── LoginViewModel ──────────────┴───────────┴──────┐
 system ──►│ merge(network, sessionEvents) ──► onIntent(intent)                   │
 streams   │                                      │                               │
           │                     reduce { copy(...) }  (pure: State -> State)     │
           │  combine(user.debounce, password, online)   ──► validation ──► reduce │
           │  submit: login().retry().onStart().onEach().catch().launchIn()       │
           └──────────────────────────────────────────────────────────────────────┘
```

- **State** (`LoginState`): one immutable data class holding everything needed to draw the screen. It always has a value and survives rotation because the ViewModel survives rotation.
- **Intent** (`LoginIntent`): everything that can happen to the screen, from the user (`UsernameChanged`) or the system (`NetworkChanged`).
- **Effect** (`LoginEffect`): things that must happen once and are not state, such as navigation or a snackbar.

**Why MVI instead of plain MVVM with many LiveData or StateFlow fields?**
- One state object means you can't end up with `isLoading = true` while an error is also showing because two fields drifted apart.
- Data flows one way, so every change goes through `onIntent` → `reduce`. You can log and replay it, and debugging is simple.
- The reducer is a pure function, so it is trivial to unit test.
- **Cost:** more boilerplate, and `copy()` allocates a new object on every change (including each keystroke). These objects are small and short-lived, so they are cheap for the young-generation GC. It only matters for very large states (see §8).

---

## 2. Step by step through the code

### Step 1: Gradle, Hilt and KSP (`app/build.gradle.kts`)
- **KSP rather than kapt**: kapt generates Java stubs for all your Kotlin code before annotation processing. KSP reads Kotlin directly, roughly 2× faster, and kapt is in maintenance mode.
- **Compose compiler plugin** (`kotlin.plugin.compose`): since Kotlin 2.0 the Compose compiler ships with Kotlin, so you no longer match compiler and Kotlin versions by hand.
- **Compose BOM**: pins every Compose artifact to versions that work together.
- `@HiltAndroidApp` on `LoginApp` generates the root Dagger component. `@AndroidEntryPoint` on `MainActivity` allows injection there and makes `hiltViewModel()` work.

### Step 2: Domain models (`domain/model/`)
| Type | Used for | Why this choice |
|---|---|---|
| `enum class PasswordStrength(label, progress)` | Fixed levels that all share the same shape | Singletons, `entries`, ordinal ordering, properties, exhaustive `when` |
| `enum class UsernameError` | Fixed reasons with no extra data | Cheapest option |
| `enum class StockStatus` + `from()` factory | Turns the API's free-text `availabilityStatus` into a closed set | Exhaustive `when` in the UI; unknown values fall back safely |
| `sealed class AppError` | Errors where **each variant carries different data** (`Server(code)`, `Unknown(message)`) | An enum can't do this; exhaustive `when` |
| `sealed interface LoginStatus / LoginIntent / LoginEffect / SessionEvent` | Closed hierarchies with no shared state | A class can implement several sealed interfaces; no constructor needed |
| `data object` | Variants without data (`Idle`, `Submit`) | Readable `toString()`, correct `equals`, a single instance |
| `@JvmInline value class AuthToken` | Type safety | At runtime it's just a `String` (no wrapper allocation) unless it is boxed as nullable, generic or `Any` |

### Step 3: Repository and use cases
- **`suspend fun login()`** for a one-shot result, and **`Flow`** for values that change over time. Wrapping a single value in a Flow adds operator overhead and makes the semantics ("does this complete?") unclear.
- **Main-safety**: the repository decides its own thread with `withContext(io)` and `flowOn(io)`, so the ViewModel can call it from Main without thinking about threads.
  - `flowOn` changes the dispatcher **upstream** only. The collector stays on its own dispatcher.
- **Inject the dispatcher** (`@IoDispatcher`) instead of hard-coding `Dispatchers.IO`. Tests can then pass a `TestDispatcher` and control virtual time.
- **`LoginUseCase` uses zip**: `currentUser().zip(cartItemCount(userId)) { profile, count -> Session(...) }`. That is `GET /auth/me` and `GET /carts/user/{id}` together, because the Home header needs both.
  - zip pairs the 1st emission of A with the 1st of B, the 2nd with the 2nd, and so on.
  - It collects both flows **concurrently**, so the total time is max(me, carts), not the sum. `LoginUseCaseTest` asserts 2000 ms (1200 + max(800, 600)) with fake latencies.
  - It completes when the **shorter** flow completes.
  - **Honest alternative:** for exactly one value from each source, `coroutineScope { val a = async { ... }; val b = async { ... }; a.await() to b.await() }` is just as good and more common. zip is the better fit when **both sides are streams** that must be paired (page N of A with page N of B).

### Step 4: Dependency injection with Dagger via Hilt (`di/`)
- `@Inject constructor(...)`: Dagger can build the class itself (`ValidateUsernameUseCase`, `AuthRepositoryImpl`).
- `@Module @InstallIn(SingletonComponent::class)`: puts bindings into the app-lifetime graph.
- **`@Binds`** (in `DataModule`) maps interface → implementation. It is an abstract method with no body, so Dagger generates less code.
- **`@Provides`** (in `AppModule` and `NetworkModule`) is for types you must construct yourself: third-party classes such as `Dispatchers`, `Json`, `OkHttpClient`, `Retrofit`, or a `CoroutineScope`.
- **`@Singleton`** gives one instance per component. Without it, every injection point gets a **new** instance. That would be a bug for `InMemorySessionEvents`, because publishers and subscribers would hold different buses.
- **Qualifiers** (`@IoDispatcher`, `@ApplicationScope`): Dagger resolves dependencies by type, so two `CoroutineDispatcher` bindings need a label.
- `@HiltViewModel` together with `hiltViewModel()` gives you a constructor-injected ViewModel without writing a `ViewModelProvider.Factory`.

Hilt components and scopes:

| Component | Scope annotation | Lives as long as |
|---|---|---|
| SingletonComponent | `@Singleton` | Application |
| ActivityRetainedComponent | `@ActivityRetainedScoped` | Activity, surviving rotation |
| ViewModelComponent | `@ViewModelScoped` | One ViewModel |
| ActivityComponent | `@ActivityScoped` | Activity instance |
| FragmentComponent | `@FragmentScoped` | Fragment |
| ViewComponent | `@ViewScoped` | View |

### Step 5: Contract (`ui/login/LoginContract.kt`)
- `LoginState` gives every field a default value, so `LoginState()` is a valid initial screen.
- **Derived properties** (`isLoading`, `canSubmit`) are computed from other fields and never stored, so they can't fall out of sync.
- `@Immutable` tells Compose the object never mutates, so composables that receive an equal state can be skipped.

### Step 6: ViewModel (`ui/login/LoginViewModel.kt`)

**StateFlow for state**
- It always has a value, which is what the UI needs to render.
- It is **conflated**: a slow collector only sees the latest value.
- It is **distinct**: setting a value equal to the current one emits nothing, so Compose only sees real changes.
- The pattern is `private val _state = MutableStateFlow(...)` with `val state = _state.asStateFlow()` exposed, so only the ViewModel can write.

**`_state.update { }` vs `_state.value = _state.value.copy()`**
- `update` is an atomic compare-and-set loop.
- The read-modify-write version can **lose an update** when two coroutines (for example the validation collector and the login collector) write at the same time.

**Channel for effects, and why not SharedFlow**

| | Background (no collector) | After rotation | Delivered to |
|---|---|---|---|
| `SharedFlow(replay=0)` | **Dropped**: navigation is lost | Not repeated | All subscribers |
| `SharedFlow(replay=1)` | Kept | **Re-delivered**: you navigate twice | All subscribers |
| `Channel(BUFFERED)` + `receiveAsFlow()` | Buffered until collected | Not repeated | **Exactly one** collector, once |

Caveat worth mentioning in interviews: Google's guidance (in the ViewModel events article) says that even a Channel has a tiny window where an event can be lost if the collector is cancelled at the wrong moment. The strictest alternative is to model the event as **state** (`status = Success`): the UI reacts to it and then calls `onNavigated()` to reset it.

**Where SharedFlow *is* the right tool**: `InMemorySessionEvents` is an app-wide **broadcast**. Many screens may subscribe, every subscriber should get every event, and late subscribers should not receive stale events (`replay = 0`). Its parameters:
- `replay`: how many old values a new subscriber receives.
- `extraBufferCapacity`: lets `emit` return without suspending while slow subscribers catch up.
- `onBufferOverflow` (`SUSPEND` / `DROP_OLDEST` / `DROP_LATEST`): what happens when the buffer is full.

**merge** (in `observeSystemEvents`): network changes and session events are two independent streams of the *same kind of thing* (events). merge turns them into one stream of intents that goes through the same `onIntent`. It collects all sources concurrently and forwards each value as it arrives, with no pairing and no waiting.

**combine** (in `observeFormValidation`): validity depends on the **latest** username, password **and** connectivity together. combine re-runs its lambda whenever **any** source emits, using the latest values of the others. It waits until every source has emitted once.

**Operators in the submit pipeline, and why their order matters**
```kotlin
login(username, pwd)
  .retry(2) { cause -> isNetwork(cause).also { if (it) delay(1000) } } // re-subscribes upstream only
  .onStart { reduce { copy(status = Loading) } }   // below retry, so it runs ONCE
  .onEach  { reduce(Success); _effects.send(NavigateToHome) }
  .catch   { reduce(Error) }   // catches upstream errors, including those thrown in onEach
  .launchIn(viewModelScope)    // same as viewModelScope.launch { collect() }
```
- `catch` only sees exceptions from **above** it. It does not catch `CancellationException`, so cancellation still propagates.
- `retry`'s predicate is a `suspend` function, so you get exponential backoff by calling `delay` inside it.
- `loginJob?.cancel()` and the `isLoading` check guard against a double tap.

**Never debounce the TextField value itself.** Compose's `TextField` needs the new text synchronously, or the cursor jumps and characters get lost. That's why `UsernameChanged` reduces immediately and only the **validation** derived from it is debounced.

### Step 7: UI (`ui/login/LoginScreen.kt`)
- **`LoginRoute` vs `LoginScreen` (state hoisting)**: the route is stateful (ViewModel, Hilt, lifecycle). The screen is stateless (`state` in, `onIntent` out), which makes it previewable, screenshot-testable and reusable.
- `collectAsStateWithLifecycle()` stops collecting when the app drops below `STARTED`, which saves CPU and battery. That lets an upstream `WhileSubscribed` stop its work too.
- **`LaunchedEffect(keys)`** starts a coroutine tied to the composition. It is cancelled when the composable leaves and restarted when a key changes.
- **`repeatOnLifecycle(STARTED)`** collects effects only while the screen is visible. The Channel buffers effects in the meantime.
- **`rememberCoroutineScope()`** is for launching from callbacks. `showSnackbar` suspends until the snackbar is dismissed, so it runs in its own coroutine instead of blocking the effect collector.
- **`rememberUpdatedState(onLoggedIn)`** lets the long-lived effect always call the latest lambda without restarting.
- `remember { PasswordVisualTransformation() }` and `remember(viewModel) { viewModel::onIntent }` avoid allocating a new object on every recomposition.
- `LinearProgressIndicator(progress = { progress })` uses the lambda overload, so the value is read in the **draw** phase and the animation doesn't recompose on every frame.

### Step 8: Tests (`app/src/test`)
- `MainDispatcherRule` replaces `Dispatchers.Main` (which doesn't exist on the JVM) with a `StandardTestDispatcher`.
- `runTest` uses **virtual time**: a 5.6-second retry scenario runs in milliseconds, and `currentTime` lets you assert exact timings.
- `runCurrent()` runs tasks scheduled for now, `advanceTimeBy(ms)` moves the clock forward, and `advanceUntilIdle()` runs everything that is pending.
- Constructor injection means the ViewModel is built by hand in tests, with no Hilt test runner needed.
- Optional: the **Turbine** library (`flow.test { awaitItem() }`) makes asserting sequences of emissions nicer.

---

## 3. Coroutine scopes cheat sheet

| Scope | Dispatcher / Job | Cancelled when | Use it for |
|---|---|---|---|
| `viewModelScope` | `Main.immediate` + `SupervisorJob` | `onCleared()` | Screen business logic |
| `lifecycleScope` | `Main.immediate` | Lifecycle is DESTROYED | Activity/Fragment work, usually with `repeatOnLifecycle` |
| `LaunchedEffect` | Composition's dispatcher | Leaves composition or a key changes | Side effects driven by state |
| `rememberCoroutineScope()` | Composition | Leaves composition | Launching from callbacks (onClick) |
| `@ApplicationScope` (injected) | `Default` + `SupervisorJob` | Process dies | Work that must outlive a screen (`stateIn` for shared singletons, analytics, saving) |
| `GlobalScope` | — | Never | **Avoid**: not cancellable, not testable, leaks |
| `coroutineScope { }` | Inherits | — | One child fails → all siblings are cancelled (all-or-nothing) |
| `supervisorScope { }` | Inherits | — | A child failure doesn't cancel its siblings (independent tasks) |

**Why `Main.immediate`?** If you are already on the main thread it runs right away instead of posting to the Handler queue. That avoids an extra frame of delay and a flicker.

---

## 4. Flow types compared

| | Cold `Flow` | `StateFlow` | `SharedFlow` | `Channel` | `LiveData` |
|---|---|---|---|---|---|
| Hot or cold | Cold (runs per collector) | Hot | Hot | Hot | Hot |
| Initial value | No | **Required** | Optional (replay) | No | Optional |
| Equal values | Emitted | **Skipped** | Emitted | Emitted | Emitted |
| Consumers | Each gets its own run | All see the latest | All get each value | **One** gets each value | All observers |
| Operators | All | All | All | Via `receiveAsFlow` | Few (`map`, `switchMap`) |
| Lifecycle-aware | No | No (use `collectAsStateWithLifecycle`) | No | No | **Yes** |
| Platform | Pure Kotlin, KMP | KMP | KMP | KMP | Android only |
| Use for | Repositories, one run per request | **UI state** | **Broadcast events** | **One-shot effects** | Legacy View code |

---

## 5. zip, combine and merge (plus the operators you'll be asked about)

```
time     : 0   2   4   6   8   10  12  14  16  18
A        :     1               2           3
B        :         a                 b           c

zip      :         (1,a)             (2,b)       (3,c)     pairs by index, waits for its partner
combine  :         1a          2a    2b    3b    3c        latest of each, on ANY emission
merge    :     1   a           2     b     3     c         all values, interleaved, no pairing
```

| Operator | Emits when | Holds in memory | Completes when | Typical use |
|---|---|---|---|---|
| `zip` | Both sides have an unpaired item | Buffered unmatched items from the faster side | **Either** side completes | Pair parallel results (profile + cart count) |
| `combine` | **Any** source emits (after all have emitted once) | Latest value of each source: O(k) | **All** sources complete | Form validation, filters + data |
| `merge` | **Any** source emits | Nothing extra: O(1) per source | **All** sources complete | Several event sources → one stream |

Other operators that come up often:
- `flatMapLatest`: cancels the previous inner flow when a new value arrives. Classic use is **search as you type**.
- `flatMapConcat`: inner flows run one after another, in order.
- `flatMapMerge(concurrency)`: inner flows run in parallel, up to N at a time.
- `debounce(t)`: emits only after t ms of silence. Use it for typing.
- `sample(t)`: emits the latest value every t ms. Use it for fast sensor or progress data.
- `distinctUntilChanged`: drops consecutive duplicates.
- `stateIn` / `shareIn`: turn a cold flow into a hot one shared by many collectors, so expensive upstream work runs once.
  - `Eagerly`: start immediately, never stop.
  - `Lazily`: start on the first subscriber, never stop.
  - `WhileSubscribed(5000)`: stop 5 s after the last subscriber leaves. A rotation takes less than 5 s, so upstream isn't restarted, but it does stop in the background.
- `buffer` / `conflate`: decouple a slow collector from a fast producer. `conflate` keeps only the latest value.
- `flowOn`: changes the upstream dispatcher.
- `catch`, `retry` / `retryWhen`, `onStart`, `onCompletion`, `onEach`: lifecycle and error handling.

---

## 6. Time and space complexity notes

| Thing | Time | Space | Notes |
|---|---|---|---|
| `StateFlow.value` read | O(1) | O(1) | |
| `StateFlow.update {}` | O(1) per attempt, plus `equals` | O(1) | Retries under contention. `equals` on a data class is O(fields), but **O(n) if a field is a big list** |
| `data class copy()` | O(fields) | New object | Shallow: lists are shared, not copied |
| `SharedFlow.emit` | O(subscribers) | O(replay + extraBuffer) | |
| `Channel.send/receive` | O(1) | O(capacity) | BUFFERED = 64 |
| `combine` of k flows | O(k) per emission plus the lambda | O(k) | Latest value per source |
| `zip` | O(1) per pair | O(unmatched items buffered) | |
| `merge` of k flows | O(1) per item | O(k) coroutines | |
| `debounce` | O(1) per item | O(1) | One timer |
| `distinctUntilChanged` | O(equals) | O(1) | Keeps the previous value |
| `PasswordStrength.of` | **O(n)**, one pass | **O(1)** | No regex, no list |
| Username regex `matches` | Usually O(n) | O(1) | Precompiled once; badly written regexes can backtrack badly |
| Dagger lookup | O(1) via generated factories | Graph built at **compile time** | Missing binding = **build error** |
| Koin lookup | Map lookup at runtime | Runtime registry | Missing binding = **runtime crash** |
| `Column` vs `LazyColumn` | Composes **all** vs only **visible** items | O(n) vs O(visible) | Use lazy lists for anything longer than a screen |

---

## 7. Dagger vs Hilt vs Koin vs manual DI

| | Dagger | Hilt | Koin | Manual |
|---|---|---|---|---|
| How it resolves | Compile-time codegen | Compile-time (it *is* Dagger) | Runtime service locator | Your own code |
| Errors show up | At build | At build | At runtime | At build |
| Runtime cost | Lowest | Lowest | Small (lookup and reflection-free DSL) | Lowest |
| Build time | Slower (annotation processing) | Slower | Fastest | Fast |
| Boilerplate | High (components, factories) | Low (standard components) | Low | Grows with the app |
| Android integration | DIY | ViewModel, WorkManager, Navigation built in | Good | DIY |
| Best for | SDKs, non-Android modules, custom graphs | **Most Android apps** | KMP, small or medium apps, fast builds | Tiny apps, learning |

**Why this demo uses Hilt:** it is Dagger underneath (compile-time safety and speed) with standard components and `@HiltViewModel`, so there's no factory boilerplate.

**Raw Dagger** means writing `@Component` interfaces, `@Component.Factory`, and calling `inject(this)` yourself. You still need it for library modules or graphs that don't follow Android lifecycles.

---

## 8. How to optimise further (production checklist)

**State and logic**
- `SavedStateHandle`: keep the **username** across process death (`savedStateHandle.getStateFlow("username", "")`). **Never store the password** there.
- Give the login screen its own ViewModel scope with Navigation Compose, so the password is cleared from memory once the user leaves the screen.
- Large lists in state: use `kotlinx.collections.immutable` (`PersistentList`). It gives Compose stability and makes `equals` cheaper because unchanged structure is shared.
- Move heavy validation off Main with `flowOn(Dispatchers.Default)` (not needed here because it's O(n) on a short string).
- Model important one-off events as state if you need guaranteed delivery (§2 Step 6).
- Return `@StringRes` or a `UiText` wrapper instead of hard-coded strings, so text can be localised.

**Compose performance**
- **Stability:** mark state classes `@Immutable` or `@Stable`. Strong skipping mode is on by default since Kotlin 2.0.20, but unstable parameters are still compared by reference.
- **Defer reads:** prefer lambda modifiers and APIs (`Modifier.offset { }`, `graphicsLayer { }`, `progress = { }`) so fast-changing values are read in the layout or draw phase instead of composition.
- Use `derivedStateOf` when a value derived from rapidly changing state changes less often (for example, "show the FAB when the first visible index > 0").
- `remember` expensive objects, and give `LazyColumn` items stable `key`s.
- Ship **Baseline Profiles** and R8 full mode to cut startup time and jank.
- Consider the newer `TextFieldState`-based `TextField` API, which removes the async-update problem by design.

**Security and UX**
- Store tokens with Android Keystore-backed encryption (DataStore + Keystore).
- Use Credential Manager (passkeys and saved passwords) and autofill semantics.
- Rate limiting and lockout belong on the server; the client only reflects them (`AccountLocked`).

---

## 9. Interview Q&A

**Q1. Why MVI and not MVVM?**
MVI is MVVM with stricter rules: one immutable state, one entry point for intents, and a pure reducer. That makes states consistent, debuggable and testable. Plain MVVM with several observable fields can produce inconsistent combinations. The trade-off is more boilerplate, which is overkill for trivial screens.

**Q2. Why StateFlow and not LiveData?**
StateFlow is pure Kotlin (testable on the JVM, works in KMP), has the full set of Flow operators, needs an initial value, and plugs into coroutines and structured concurrency. LiveData's only advantage, lifecycle awareness, is covered by `collectAsStateWithLifecycle` and `repeatOnLifecycle`.

**Q3. Why not `mutableStateOf` in the ViewModel?**
It works and is fine in Compose-only apps. StateFlow keeps the ViewModel UI-toolkit-agnostic (Views, KMP, tests), and makes Flow operators (`combine`, `debounce`, `stateIn`) natural to use.

**Q4. StateFlow vs SharedFlow?**
StateFlow is a SharedFlow with `replay = 1`, a mandatory initial value, conflation and `distinctUntilChanged`. Use StateFlow for **state** and SharedFlow for **broadcast events** where every subscriber needs every value (including equal ones) and there's no "current value".

**Q5. Why a Channel for one-time effects?**
A SharedFlow with `replay = 0` drops events when nobody is collecting (app in the background). With `replay = 1` it replays them after rotation. A Channel buffers the event and delivers it once to one collector. See §2 Step 6 for the caveat and the alternative of modelling events as state.

**Q6. Hot vs cold flows?**
A cold flow (`flow {}`) runs its producer separately for each collector, and only while it's collected. A hot flow (StateFlow, SharedFlow, Channel) exists and emits regardless of collectors. Use `stateIn` or `shareIn` to share one upstream between many collectors.

**Q7. `collectAsState` vs `collectAsStateWithLifecycle`?**
The first keeps collecting in the background. The second stops below `STARTED` (configurable), which saves work and lets `WhileSubscribed` stop upstream.

**Q8. `LaunchedEffect` vs `rememberCoroutineScope` vs `DisposableEffect` vs `SideEffect`?**
- `LaunchedEffect`: a coroutine driven by composition and its keys.
- `rememberCoroutineScope`: launch from event callbacks.
- `DisposableEffect`: register and unregister non-coroutine resources with `onDispose`.
- `SideEffect`: run after every successful recomposition to push Compose state to non-Compose code.

**Q9. Which dispatcher does `viewModelScope` use, and why `SupervisorJob`?**
`Dispatchers.Main.immediate`. It uses `SupervisorJob` so one failed child coroutine doesn't cancel the whole ViewModel scope.

**Q10. `launch` vs `async`? `coroutineScope` vs `supervisorScope`?**
- `launch` is fire-and-forget and returns a `Job`. `async` returns a `Deferred` result.
- In `coroutineScope`, one failure cancels all siblings (all-or-nothing). In `supervisorScope`, children fail independently.

**Q11. How do you handle errors in a Flow?**
- `catch` handles **upstream** exceptions only.
- `retry` / `retryWhen` re-subscribe.
- `onCompletion(cause)` sees completion and errors.
- Never swallow `CancellationException`; rethrow it if you use try/catch inside a coroutine.
- `CoroutineExceptionHandler` only applies to root coroutines started with `launch`.

**Q12. zip vs combine vs merge?** See §5.
- zip pairs by index (parallel results that belong together).
- combine uses the latest of each source (derived state).
- merge interleaves everything (multiple event sources).
- Memory: combine keeps O(k) latest values, merge adds O(1) per item, zip buffers unmatched items.

**Q13. flatMapLatest vs flatMapConcat vs flatMapMerge?**
- Latest cancels the previous inner flow (search).
- Concat runs inner flows sequentially and in order.
- Merge runs them concurrently up to a limit (parallel downloads).

**Q14. debounce vs sample (throttle)?**
- debounce waits for quiet: the final value after typing stops.
- sample emits periodically: it limits the rate of a continuous stream.

**Q15. Why `WhileSubscribed(5000)`?**
It survives configuration changes (under 5 s) without restarting expensive upstream work (network, DB, sensors), and still stops when the app is really in the background.

**Q16. `update {}` vs assigning `.value`?**
`update` is an atomic compare-and-set retry loop. Assigning `.value` after reading it is a non-atomic read-modify-write and can lose concurrent updates.

**Q17. enum vs sealed class vs sealed interface?**
- **enum:** a fixed set of identical-shaped singletons, with `entries`, ordinal and ordering.
- **sealed class:** a fixed set of *types*, each of which can have different data and multiple instances.
- **sealed interface:** the same as a sealed class but with no constructor or state, and a class can implement several.
- All three give exhaustive `when`.

**Q18. Why `data object`?**
A singleton with a readable `toString()` (`Idle` instead of `LoginStatus$Idle@1a2b`) and correct `equals`/`hashCode`. It's the idiomatic choice for variants without data.

**Q19. What does a value class give you?**
Compile-time type safety (you can't pass a username as an `AuthToken`) without allocating a wrapper at runtime. It gets boxed when used as nullable, generic or `Any`.

**Q20. Dagger vs Hilt vs Koin?** See §7.
Dagger and Hilt are compile-time and fail at build. Koin is a runtime locator and fails at runtime, but builds faster. Hilt is Dagger with Android-standard components.

**Q21. `@Binds` vs `@Provides`?**
- `@Binds` maps interface → implementation. It is abstract and generates less code.
- `@Provides` is for when you must write the construction code (third-party types, builders, config).

**Q22. What happens if you forget `@Singleton`?**
Every injection gets a new instance. For stateful objects (an event bus, caches, a `stateIn` monitor) that's a bug: publishers and subscribers end up holding different instances.

**Q23. Why qualifiers?**
Dagger resolves by type. Two `CoroutineDispatcher` or `CoroutineScope` bindings need `@IoDispatcher` / `@ApplicationScope` to tell them apart, or compilation fails.

**Q24. Why inject dispatchers?**
To make tests deterministic: inject a `TestDispatcher` and control virtual time. Hard-coded `Dispatchers.IO` makes tests slow and flaky.

**Q25. How do you reduce recomposition?**
- Use stable, immutable state and hoist it.
- Pass the narrowest parameters you can.
- Read fast-changing values late (lambda modifiers, draw phase).
- Use `derivedStateOf`, `remember`, and `key`s in lazy lists.
- Avoid unstable types such as `List` without immutable collections.
- Check with Layout Inspector recomposition counts and the Compose compiler reports.

**Q26. Why a stateless `LoginScreen`?**
State hoisting gives a single source of truth, plus previews, screenshot tests and reuse. The screen has no idea that a ViewModel or Hilt exists.

**Q27. Configuration change vs process death: what survives?**
- **Rotation:** the ViewModel survives, so the StateFlow still has its value. `remember` is lost, `rememberSaveable` survives.
- **Process death:** the ViewModel is gone. Only `SavedStateHandle`, `rememberSaveable` and persistent storage survive.

**Q28. Why `repeatOnLifecycle` for collecting effects?**
Collecting in `lifecycleScope.launch` directly keeps collecting in the background and can update UI that isn't visible. `repeatOnLifecycle(STARTED)` cancels collection on stop and restarts it on start.

**Q29. Why debounce validation but not the text field?**
TextField must receive its new value synchronously or the cursor jumps and input is lost. Validation is derived data, so delaying it improves UX (no error shown while typing) and saves work.

**Q30. Why use-case classes at all?**
They hold business rules (login → profile ZIP cart count) independent of UI and data, so they're reusable and testable. Skip them when they would only pass calls straight through to a repository.

**Q31. `StandardTestDispatcher` vs `UnconfinedTestDispatcher`?**
- Standard queues coroutines and you advance them explicitly. It is deterministic and lets you check intermediate states like `Loading`.
- Unconfined runs eagerly. It is simpler, but you can miss intermediate states.

**Q32. Common coroutine memory leaks?**
- `GlobalScope`.
- Collecting without lifecycle awareness.
- Holding an Activity `Context` in a ViewModel or singleton (use `@ApplicationContext`).
- Forgetting `awaitClose { unregister }` in `callbackFlow`.
- Never-cancelled `Job`s in custom scopes.

---
---

# Part 2: Home list, Detail page and the live REST API

## 10. App flow

```
 LoginDestination(sessionExpired)            HomeDestination                     ProductDetailDestination(id)
 ┌───────────────────────────┐   success   ┌──────────────────────────────┐ tap ┌───────────────────────────┐
 │ POST /auth/login          │────────────►│ GET /products?limit&skip     │────►│ GET /products/{id}        │
 │ GET /auth/me  ─┐ zip      │ popUpTo(all)│ GET /products/search?q=      │     │ images · price · stock    │
 │ GET /carts/user/{id} ─┘   │             │ GET /products/category/{slug}│◄────│ warranty · reviews        │
 └───────────────────────────┘◄────────────│ GET /products/categories     │back └───────────────────────────┘
          ▲  Logout / Expire / real 401     └──────────────────────────────┘
          └──── SharedFlow<SessionEvent> ◄── AuthInterceptor (OkHttp thread)
```

Layers (the dependencies only point inward):
```
ui (Compose + ViewModels) ──► domain (models, repository interfaces, use cases) ◄── data (Retrofit, DTOs, repo impls)
```
`domain` has no Android or Retrofit imports. That's why every ViewModel test runs on the plain JVM with fakes.

**Why DummyJSON?** It's free and open source, with no API key. It has **real JWT auth** (login, `/auth/me`, refresh), users, carts and about 190 products with images, ratings, stock, warranty, shipping and reviews, plus pagination (`limit`/`skip`), search, categories and field selection (`select`). One API covers everything the requirement asks for.

---

## 11. Networking: Retrofit + OkHttp + kotlinx.serialization (`data/network/`)

### Pieces
| Piece | Responsibility |
|---|---|
| `DummyJsonApi` | Retrofit interface. `suspend` functions are cancellable: cancelling the coroutine cancels the HTTP call |
| `Dtos.kt` | `@Serializable` classes that mirror the JSON, with defaults on optional fields |
| `Mappers.kt` | DTO → domain (`ProductDto.toSummary()` / `toDetail()`) |
| `ErrorMapping.kt` | `HttpException` / `IOException` → sealed `AppError`; `safeApiCall` and `mapToAppErrors()` |
| `AuthInterceptor` | Adds the bearer token **only** to `@Authenticated` endpoints. On a 401 it publishes `SessionEvent.Expired` |
| `TokenStore` | `@Volatile` in-memory token |
| `NetworkModule` | `@Provides` one `Json`, one `OkHttpClient`, one `Retrofit` (`@Singleton`) |

### Why these choices
- **DTO vs domain model:** DTOs follow the server (nullable, stringly typed). Domain models follow the app. If the API renames a field, only the DTO and mapper change. `StockStatus.from()` turns free text into an enum at this boundary.
- **kotlinx.serialization vs Gson vs Moshi:**
  - kotlinx.serialization generates code at compile time (no reflection), respects Kotlin default values and nullability, and works in KMP.
  - Gson uses reflection and **ignores Kotlin defaults and non-null types**: a missing field becomes `null` inside a non-null `String`, which crashes later.
  - Moshi (with codegen) is also a good choice, but it's JVM-only.
- **`ignoreUnknownKeys = true` + defaults:** the server can add fields without breaking old app versions.
- **Retrofit vs Ktor client:** Retrofit is the Android standard, with annotation-based endpoints, OkHttp underneath and a huge ecosystem. Ktor client is the choice for **Kotlin Multiplatform**. Volley and `HttpURLConnection` are legacy.
- **One `OkHttpClient`:** each client has its own connection pool and dispatcher threads. Sharing one reuses HTTP/2 connections and saves memory.
- **`select` query param** (`LIST_FIELDS`): list rows ask for 8 fields instead of the whole product (description, reviews, images, meta…). The payload is roughly 10× smaller, and parsing and memory drop with it.
- **`@Authenticated` + `Invocation` tag:** auth is declared next to each endpoint. A list of URLs inside the interceptor would silently break when a new endpoint is added.
- **Interceptor → SharedFlow:** interceptors run on OkHttp threads and aren't coroutines, so they use `tryPublish` (non-suspending). Any screen can react to the broadcast. Home logs out and navigates to Login.
- **`HttpLoggingInterceptor.redactHeader("Authorization")`:** tokens never appear in logcat. Logging is off in release builds.
- **`@Volatile` token:** it's written on an IO thread and read on OkHttp threads. Volatile guarantees **visibility**. A single reference write is already atomic, so no lock is needed.

### Error handling and the `CancellationException` trap
```kotlin
try { block() }
catch (e: CancellationException) { throw e }          // ALWAYS rethrow
catch (e: Exception) { throw AppException(e.toAppError()) }
```
`runCatching {}` or a bare `catch (e: Exception)` also catches `CancellationException`. When that happens, a coroutine whose screen was closed keeps running and may write to state: a leak and a correctness bug. Home's `loadCategories` catches **only** `AppException` for the same reason.

### Production upgrades
- Use an OkHttp **`Authenticator`** that calls `POST /auth/refresh` with the refresh token and retries once before declaring the session expired.
- Persist the token in DataStore, encrypted with an Android Keystore key.
- Add an OkHttp disk `Cache` with `Cache-Control` headers.

---

## 12. Type-safe Navigation (`ui/navigation/AppNavHost.kt`)

```kotlin
@Serializable data class LoginDestination(val sessionExpired: Boolean = false)
@Serializable data object HomeDestination
@Serializable data class ProductDetailDestination(val id: Int)

navController.navigate(ProductDetailDestination(id))      // compile-checked, no "detail/{id}" strings
```
- **Each destination gets its own `ViewModelStore`**, so `hiltViewModel()` is scoped to that screen. `LoginViewModel`, which holds the password, is cleared once Login is popped.
- **`popUpTo(navController.graph.id) { inclusive = true }`** after login and logout clears the whole back stack. Back on Home exits the app instead of returning to Login, and Back on Login after a logout doesn't return to Home.
- **Arguments land in the destination's `SavedStateHandle`** under the property names (`"id"`, `"sessionExpired"`), so they **survive process death**. The ViewModels read them with `savedStateHandle.get<Int>("id")`. That's equivalent to `toRoute<ProductDetailDestination>()`, but it keeps plain-JVM unit tests free of Android `Bundle` dependencies.
- **Pass the id, not the object.** Navigation arguments go into a `Bundle`, which has size limits (`TransactionTooLargeException`). Passing the id also means the detail screen shows fresh data and can be opened from a deep link.

---

## 13. Home list (`ui/home/`)

### State design: why a data class here, and a sealed class on Detail
A list screen is often in **several states at once**: showing results while the next page loads, or showing old results while a new search runs. A data class with flags (`isLoading`, `isLoadingMore`, `endReached`, `error`) models that overlap. A sealed `Loading | Content | Error` can only be in one branch at a time. Detail is exactly one of those three, so it uses sealed (§14).

### The filter pipeline: combine + flatMapLatest
```kotlin
combine(query.debounce { if (it.isEmpty()) 0 else 400 }, category, reloadTrigger) { q, c, _ -> ProductQuery(q, c) }
    .flatMapLatest { filter ->
        flow { emit(repo.products(filter, skip = 0, limit = 20)) }
            .onStart { loadMoreJob?.cancel(); reduce { copy(isLoading = true, isLoadingMore = false, error = null) } }
            .onEach  { page -> reduce { copy(isLoading = false, products = page.items, endReached = page.endReached) } }
            .catch   { e -> reduce { copy(isLoading = false, products = emptyList(), error = e.toAppError()) } }
    }
    .launchIn(viewModelScope)
```
- **combine:** the list depends on the latest search text, category **and** a retry counter together.
- **`debounce { }` with a lambda:** it waits 400 ms while you type, but reloads **instantly** when the box is cleared.
- **flatMapLatest:** when a new filter arrives, the request for the old one is **cancelled** (and Retrofit cancels the HTTP call). Without it, the slow response for "pho" could arrive after "phone" and overwrite newer results. `HomeViewModelTest` proves the stale category result never lands.
- **`reloadTrigger` (a `MutableStateFlow<Int>`):** Retry increments it so combine re-emits the same filter. A StateFlow ignores equal values, so you can't just re-set the filter.
- **Search and category are mutually exclusive** because DummyJSON can't do both at once. The **reducer** enforces it: typing clears the category, and picking a chip clears the search. Business rules live in the reducer, not scattered across the UI.

### Pagination (manual offset paging)
- `loadMore()` asks for `skip = products.size, limit = 20` and **appends** the page.
- **Guards:** it returns early while loading, while already loading more, after the end, or on error. Double triggers from scrolling become no-ops, and a test checks this.
- `loadMoreJob?.cancel()` runs when the filter changes, so a page-3 response for the old filter can't be appended to new results.
- `(products + page.items).distinctBy { it.id }` costs O(n) time and space. It protects LazyColumn from **duplicate keys, which crash** if the server's data shifts between pages.
- `activeQuery` remembers the filter the list was actually loaded with. The text box may be mid-debounce.

**Manual paging vs Paging 3:**

| | Manual (this demo) | Paging 3 |
|---|---|---|
| Code | ~30 lines, fully visible | `PagingSource`, `Pager`, `LazyPagingItems` |
| Memory | Grows with every page | Can drop far-away pages (`maxSize`) |
| Offline | DIY | `RemoteMediator` + Room = offline-first |
| Retry, refresh, placeholders | DIY | Built in |
| Fits MVI single state | Naturally | `PagingData` lives beside state, not inside it |

Use Paging 3 for long or infinite feeds and offline caching. Manual paging is fine for short catalogues, and easier to explain and test.

### Triggering "load more" from Compose
```kotlin
val shouldLoadMore by remember { derivedStateOf {
    val info = listState.layoutInfo
    info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 5
} }
LaunchedEffect(listState) { snapshotFlow { shouldLoadMore }.distinctUntilChanged().filter { it }.collect { onLoadMore() } }
```
- **`derivedStateOf`:** the scroll position changes every frame, but the Boolean flips rarely. Readers only react when the **Boolean** changes, which avoids recomposition on every pixel scrolled.
- **`snapshotFlow`** turns Compose state into a Flow, so you can use `distinctUntilChanged` and `filter` on it.
- A prefetch distance of 5 starts loading before the user hits the bottom.

### LazyColumn performance
- `key = { it.id }` gives a stable identity: rows keep their state across inserts and unchanged rows are skipped.
- `contentType` lets Compose reuse compositions between items of the same type (product vs footer).
- A Lazy list composes only **visible** items (O(visible) instead of O(n) for a `Column`).

### Images with Coil
Coil is coroutine-based and Compose-first, with memory and disk caches. It **downsamples to the target size**: an 80 dp thumbnail isn't decoded at full resolution. Glide is also fine and has a Compose integration. Coil is lighter, written in Kotlin, and Coil 3 supports KMP.

### Categories cache with a `Mutex`
```kotlin
categoriesMutex.withLock { cached ?: api.categories().also { cached = it } }
```
`Mutex` **suspends** where `synchronized` would block a thread. Two concurrent callers make **one** network call. After the first load it's an O(1) memory read.

---

## 14. Detail page (`ui/detail/`)

### Sealed UI state
```kotlin
sealed interface ProductDetailState {
    data object Loading : ProductDetailState
    data class Error(val error: AppError) : ProductDetailState
    data class Content(val product: ProductDetail) : ProductDetailState
}
```
`Content` can't exist without a product: no `product: ProductDetail?` and no `isLoading && product != null` combinations. The UI's `when` is exhaustive.

### The derived-state pattern: `stateIn` instead of a `MutableStateFlow`
```kotlin
val state = retryCount
    .flatMapLatest { attempt -> flow { if (attempt > 0) emit(Loading); emit(Content(repo.product(id))) }
                                   .catch { emit(Error(it.toAppError())) } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loading)
```
- Use this when the state is a **pure function of its inputs** (the id and a retry counter) and there are few intents. There's no `reduce`, and the state can't be modified from anywhere else.
- Use `MutableStateFlow` + `reduce` (as Login and Home do) when many intents edit parts of the state.
- **`WhileSubscribed(5_000)` trade-off:**
  - Rotation (under 5 s) doesn't refetch.
  - After more than 5 s in the background, upstream stops. On return it refetches for fresh price and stock, while the StateFlow **still shows the last content**. Only retries emit `Loading`, so the screen doesn't flash a spinner.
  - Want no refetch at all? Use `SharingStarted.Lazily`, or cache in the repository.
- **Testing `stateIn`:** upstream runs only while there is a subscriber, so the test collects in `backgroundScope` (see `ProductDetailViewModelTest`).

### UI decisions
- **One `LazyColumn` for the whole screen.** Reviews are lazy `items`. Putting a `LazyColumn` inside `verticalScroll` crashes (infinite height constraints). A plain `Column` of 100 reviews would compose all of them up front.
- **`HorizontalPager`** for images is lazy too: only the current page and its neighbours are composed and loaded.
- **`StockBadge`** uses an exhaustive `when` over the `StockStatus` enum, so adding an enum value forces a colour choice.
- **Shared `PriceText` and `RatingText`** live in `ui/common`. `discountedPrice()` is a pure domain function, unit-tested once and used by both screens.

---

## 15. Complexity and optimisation additions

| Thing | Time | Space | Note |
|---|---|---|---|
| Append a page (`products + page`) | O(n + k) | New list O(n + k) | Persistent lists make it about O(k) with structural sharing |
| `distinctBy { id }` | O(n) | O(n) HashSet | Prevents duplicate-key crashes |
| `flatMapLatest` switch | O(1) plus cancelling the old request | O(1) | Saves bandwidth too: the old HTTP call is cancelled |
| `debounce` on search | O(1) per keystroke | O(1) | Turns N keystrokes into 1 request |
| Categories cache | O(1) after the first load | O(categories) | Mutex: one fetch even with concurrent callers |
| Lazy list composition | O(visible items) | O(visible) | vs `Column`: O(n) |
| `select` fields | — | ~10× smaller payload | Less parsing and less GC |
| JSON parsing (kotlinx) | O(bytes) | O(objects) | No reflection |

**What to do next in production:**
- **Offline-first:** Room as the single source of truth. Repositories expose `Flow` from Room, and the network only refreshes the DB (Paging 3 `RemoteMediator` for the list).
- **Prefetch the detail:** the list already has title, price and thumbnail. Show those instantly on Detail (pass them in, or read a cache) and load the rest in the background.
- **Images:** request a thumbnail-sized URL when the CDN supports it, and use `crossfade` and placeholders.
- **Baseline Profiles** for the scroll path, and R8 full mode.
- **Accessibility:** content descriptions are already set on images. Add `semantics` for price and stock announcements.

---

## 16. Interview Q&A, part 2

**Q33. Why flatMapLatest for search? What's the alternative?**
It cancels the previous request when a new query arrives, which prevents out-of-order results and wasted bandwidth. `flatMapConcat` would wait for every old query to finish. `flatMapMerge` would run them all in parallel, and results could arrive out of order.

**Q34. Why debounce the search but not the text field?**
The TextField must update synchronously (cursor). Debouncing the derived request turns N keystrokes into 1 API call.

**Q35. combine vs zip for filters?**
Filters change independently, and you need the latest of each, so use combine. zip would wait for both to change before pairing them, which is wrong here.

**Q36. How do you prevent duplicate "load more" calls?**
Guard on `isLoading || isLoadingMore || endReached || error`, keep one `loadMoreJob`, and use `distinctUntilChanged` on the scroll trigger.

**Q37. Paging 3 or manual pagination?** See §13.
Use Paging 3 for infinite feeds, offline support and memory limits. Manual paging is fine for small lists and simpler with MVI.

**Q38. Why `key` in LazyColumn? What happens with duplicate keys?**
Keys give rows a stable identity (state, animations, skipping). Duplicate keys throw `IllegalArgumentException` at runtime, which is why the list uses `distinctBy`.

**Q39. `derivedStateOf` vs `remember(key)`?**
- `derivedStateOf` is for a value computed from **other State** that changes more often than the result does (scroll → Boolean).
- `remember(key)` recomputes when a **plain value** key changes.

**Q40. Why is `snapshotFlow` useful?**
It bridges Compose `State` into a `Flow` so you can apply `debounce`, `distinctUntilChanged` and `filter`, for example to report scroll analytics or trigger pagination.

**Q41. Data-class state vs sealed state?**
- Data class: states overlap (list + loading more).
- Sealed: exactly one at a time (detail: loading, error or content). Fields only exist where they make sense.

**Q42. `MutableStateFlow` + reduce vs `stateIn`?**
- `stateIn` derives state from inputs, needs no mutation and is cleaner for read-mostly screens.
- `MutableStateFlow` suits screens with many intents editing parts of the state.

**Q43. What does `WhileSubscribed(5000)` do on the Detail screen?**
It keeps upstream alive through rotation. It stops more than 5 s after leaving, and refetches on return while still showing the old content.

**Q44. Why DTOs separate from domain models?**
To isolate API changes, keep nullability and string types at the boundary, enable typed enums (`StockStatus`), and make domain tests independent of JSON.

**Q45. kotlinx.serialization vs Gson?**
kotlinx is compile-time, reflection-free, honours Kotlin defaults and non-null types, and works in KMP. Gson ignores defaults and can put `null` into non-null fields.

**Q46. Interceptor vs Authenticator in OkHttp?**
- An **Interceptor** sees every request (add headers, logging, detect 401).
- An **Authenticator** is called **only on 401**, to refresh the token and retry the request. Use both: an interceptor to attach the token and an authenticator to refresh it.

**Q47. Why rethrow `CancellationException`, and what's wrong with `runCatching` in coroutines?**
Cancellation is delivered as an exception. Swallowing it keeps a cancelled coroutine running, which breaks structured concurrency and can update a dead screen.

**Q48. Mutex vs synchronized in coroutines?**
`synchronized` blocks the thread, and you can't call suspend functions inside it. `Mutex.withLock` suspends, so it's cheap and works around suspend calls. Note that Mutex isn't reentrant.

**Q49. Why one OkHttpClient?**
For connection pooling, HTTP/2 multiplexing and shared thread pools. Several clients waste memory and sockets. Use `client.newBuilder()` to derive variants that still share the pool.

**Q50. Why pass only the product id in navigation?**
Bundles have size limits, data could go stale, and deep links only carry ids. A repository cache makes the second load instant.

**Q51. Why `popUpTo(graph.id) { inclusive = true }` after login?**
It clears the back stack so Back from Home exits instead of returning to Login, and the Login ViewModel (with the password) is destroyed.

**Q52. How would you make this app work offline?**
- Room as the single source of truth. Repositories return `Flow` from DAO queries.
- A network refresh writes to the DB, and Paging 3 `RemoteMediator` handles the list.
- `NetworkMonitor` drives a banner.
- WorkManager handles background sync.
