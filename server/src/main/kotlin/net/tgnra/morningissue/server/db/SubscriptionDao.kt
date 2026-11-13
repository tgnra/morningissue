package net.tgnra.morningissue.server.db

import androidx.room3.Dao

/** Subscriptions, tags and `subscription_tag`; every query names `user_id`. Owned by W-A (`docs/server-design.md`, section 8); empty until then. */
@Dao
interface SubscriptionDao
