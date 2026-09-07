package com.korailmacro.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Keep old internal links working; all seat queries now use the current draft in MainActivity. */
class SeatStatusActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("page", "seats"))
        finish()
    }
}
