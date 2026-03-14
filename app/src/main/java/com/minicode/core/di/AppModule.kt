package com.minicode.core.di

import android.content.Context
import androidx.room.Room
import com.minicode.data.db.AppDatabase
import com.minicode.data.db.ConnectionProfileDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "minicode.db")
            .build()

    @Provides
    fun provideConnectionProfileDao(db: AppDatabase): ConnectionProfileDao =
        db.connectionProfileDao()
}
