# MVI Login → Home list → Detail: Jetpack Compose demo on a live REST API

A small Android app that signs in, lists products (search, categories, pagination) and shows product details. It uses **DummyJSON** (`https://dummyjson.com`), a free, open-source fake REST API with real JWT auth, users, carts and about 190 products with images, stock and reviews. No API key needed.

Demo login: **`emilys` / `emilyspass`** (or tap **Use demo account**).

| Concept | Where |
|---|---|
| MVI (State / Intent / Effect / reducer) | `ui/login`, `ui/home` (`*Contract.kt`, `*ViewModel.kt`) |
| StateFlow (UI state), Channel (one-shot effects) | every ViewModel |
| SharedFlow (app-wide broadcast, fired by a real 401) | `data/InMemorySessionEvents.kt`, `data/network/AuthInterceptor.kt` |
| callbackFlow + stateIn(WhileSubscribed) | `data/ConnectivityNetworkMonitor.kt`, `ui/detail/ProductDetailViewModel.kt` |
| **zip** (`/auth/me` + `/carts/user/{id}` in parallel) | `domain/usecase/LoginUseCase.kt` |
| **merge** (network + session events → intents) | `LoginViewModel.observeSystemEvents` |
| **combine** (form validation; search + category + retry) | `LoginViewModel`, `HomeViewModel.observeFilters` |
| **flatMapLatest** (cancel stale search requests) | `HomeViewModel.observeFilters` |
| debounce, distinctUntilChanged, retry, onStart, catch, flowOn, filterIsInstance | ViewModels, repositories |
| Pagination + derivedStateOf + snapshotFlow | `HomeViewModel.loadMore`, `HomeScreen.ProductList` |
| Scopes: viewModelScope, @ApplicationScope, LaunchedEffect, rememberCoroutineScope, repeatOnLifecycle | throughout |
| enum / sealed class / sealed interface / data object / value class | `domain/model`, contracts, `ProductDetailState` |
| Dagger via Hilt: @Binds, @Provides, @Singleton, qualifiers, @HiltViewModel | `di/` |
| Retrofit + OkHttp + kotlinx.serialization, DTO → domain mapping, error mapping | `data/network/` |
| Type-safe Navigation (`@Serializable` routes, args via SavedStateHandle) | `ui/navigation/AppNavHost.kt` |
| Coil images, HorizontalPager, LazyColumn keys/contentType | `ui/home`, `ui/detail` |
| Unit tests with fakes and virtual time | `app/src/test/` |

**Read [`docs/GUIDE.md`](docs/GUIDE.md).** It walks through every step with the why and the why-not, plus optimisations, complexity tables and 52 interview Q&As.

## Run
Open the folder in Android Studio, sync, and run `app` on a device or emulator with internet. Run the tests with `./gradlew test`; they use fakes, not the network.

## Structure
```
app/src/main/java/com/swapnil/mvilogin/
├── LoginApp.kt / MainActivity.kt
├── di/              AppModule, NetworkModule (@Provides), DataModule (@Binds), Qualifiers
├── domain/
│   ├── model/       AppError (sealed), PasswordStrength/StockStatus (enum), Product, Page, AuthToken (value class)
│   ├── repository/  AuthRepository, ProductRepository, NetworkMonitor, SessionEvents
│   └── usecase/     LoginUseCase (zip), ValidateUsernameUseCase
├── data/
│   ├── network/     DummyJsonApi (Retrofit), DTOs, mappers, error mapping, AuthInterceptor
│   ├── repository/  AuthRepositoryImpl, ProductRepositoryImpl (Mutex cache)
│   └── TokenStore, ConnectivityNetworkMonitor, InMemorySessionEvents
└── ui/
    ├── navigation/  AppNavHost + @Serializable destinations
    ├── login/       LoginContract, LoginViewModel, LoginScreen, messages
    ├── home/        HomeContract, HomeViewModel, HomeScreen (search, chips, paginated list)
    ├── detail/      ProductDetailViewModel (stateIn), ProductDetailScreen (pager, reviews)
    └── common/      PriceText, RatingText, loading/error views
```
