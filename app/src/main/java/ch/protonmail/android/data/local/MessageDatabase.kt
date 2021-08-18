/*
 * Copyright (c) 2020 Proton Technologies AG
 *
 * This file is part of ProtonMail.
 *
 * ProtonMail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ProtonMail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ProtonMail. If not, see https://www.gnu.org/licenses/.
 */
package ch.protonmail.android.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import ch.protonmail.android.data.local.model.Attachment
import ch.protonmail.android.data.local.model.AttachmentTypesConverter
import ch.protonmail.android.data.local.model.LabelEntity
import ch.protonmail.android.data.local.model.Message
import ch.protonmail.android.data.local.model.MessagesTypesConverter
import ch.protonmail.android.mailbox.data.local.ConversationDao
import ch.protonmail.android.mailbox.data.local.UnreadCounterDao
import ch.protonmail.android.mailbox.data.local.model.ConversationDatabaseModel
import ch.protonmail.android.mailbox.data.local.model.ConversationTypesConverter
import ch.protonmail.android.mailbox.data.local.model.UnreadCounterEntity
import me.proton.core.data.room.db.CommonConverters

@Database(
    entities = [
        Attachment::class,
        ConversationDatabaseModel::class,
        Message::class,
        LabelEntity::class,
        UnreadCounterEntity::class
    ],
    version = 12
)
@TypeConverters(
    value = [
        CommonConverters::class,

        AttachmentTypesConverter::class,
        MessagesTypesConverter::class,
        ConversationTypesConverter::class
    ]
)
internal abstract class MessageDatabase : RoomDatabase() {

    @Deprecated("Use getMessageDao", ReplaceWith("getMessageDao()"))
    fun getDao(): MessageDao =
        getMessageDao()

    abstract fun getMessageDao(): MessageDao
    abstract fun getConversationDao(): ConversationDao
    abstract fun getUnreadCounterDao(): UnreadCounterDao

    companion object Factory : DatabaseFactory<MessageDatabase>(
        MessageDatabase::class,
        "MessagesDatabase.db"
    )
}
