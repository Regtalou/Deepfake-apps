package com.deepfakedetector.di

import android.content.Context
import androidx.room.Room
import com.deepfakedetector.analysis.*
import com.deepfakedetector.db.*
import com.deepfakedetector.network.ApiConfig
import com.deepfakedetector.network.ExternalApiService
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // ─── Base de données ──────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
        .fallbackToDestructiveMigration()
        .build()

    @Provides
    fun provideAnalysisDao(db: AppDatabase): AnalysisDao = db.analysisDao()

    @Provides
    fun provideCommunityDao(db: AppDatabase): CommunityDao = db.communityDao()

    @Provides
    fun provideBacktestDao(db: AppDatabase): BacktestDao = db.backtestDao()

    // ─── Réseau ───────────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideGson(): Gson = GsonBuilder()
        .setLenient()
        .create()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(ApiConfig.CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(ApiConfig.READ_TIMEOUT_S, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .addHeader("User-Agent", "DeepfakeDetector-Android/1.0")
                    .addHeader("Accept", "application/json")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, gson: Gson): Retrofit =
        Retrofit.Builder()
            .baseUrl(ApiConfig.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()

    @Provides
    @Singleton
    fun provideExternalApiService(retrofit: Retrofit): ExternalApiService =
        retrofit.create(ExternalApiService::class.java)

    // ─── Analyseurs ───────────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideMetadataAnalyzer(@ApplicationContext context: Context): MetadataAnalyzer =
        MetadataAnalyzer(context)

    @Provides
    @Singleton
    fun provideVideoAnalyzer(@ApplicationContext context: Context): VideoAnalyzer =
        VideoAnalyzer(context)

    @Provides
    @Singleton
    fun provideAudioAnalyzer(@ApplicationContext context: Context): AudioAnalyzer =
        AudioAnalyzer(context)

    @Provides
    @Singleton
    fun provideFaceAnalyzer(@ApplicationContext context: Context): FaceAnalyzer =
        FaceAnalyzer(context)

    @Provides
    @Singleton
    fun provideScoreFusionEngine(): ScoreFusionEngine = ScoreFusionEngine()

    @Provides
    @Singleton
    fun provideTextAnalyzer(): TextAnalyzer = TextAnalyzer()

    @Provides
    @Singleton
    fun provideCoherenceAnalyzer(@ApplicationContext context: Context): CoherenceAnalyzer =
        CoherenceAnalyzer(context)

    @Provides
    @Singleton
    fun provideMultiModalAnalyzer(
        imageAnalyzer: ImageAnalyzer,
        textAnalyzer: TextAnalyzer,
        coherenceAnalyzer: CoherenceAnalyzer
    ): MultiModalAnalyzer = MultiModalAnalyzer(imageAnalyzer, textAnalyzer, coherenceAnalyzer)

    @Provides
    @Singleton
    fun provideImageAnalyzer(@ApplicationContext context: Context): ImageAnalyzer =
        ImageAnalyzer(context)

    @Provides
    @Singleton
    fun provideBacktestEngine(
        videoAnalyzer: VideoAnalyzer,
        audioAnalyzer: AudioAnalyzer,
        faceAnalyzer: FaceAnalyzer,
        metadataAnalyzer: MetadataAnalyzer,
        scoreFusionEngine: ScoreFusionEngine
    ): BacktestEngine = BacktestEngine(
        videoAnalyzer, audioAnalyzer, faceAnalyzer, metadataAnalyzer, scoreFusionEngine
    )
}
