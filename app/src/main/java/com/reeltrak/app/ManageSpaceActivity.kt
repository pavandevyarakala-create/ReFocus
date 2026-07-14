package com.reeltrak.app

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class ManageSpaceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // This is the hijack! When the user clicks "Clear Storage" in Android Settings,
        // it launches this Activity instead of wiping the data.
        
        Toast.makeText(
            this, 
            "Action Denied: Storage cannot be cleared while ReelTrak Vault is active.", 
            Toast.LENGTH_LONG
        ).show()
        
        // Immediately close the activity to send them back to settings
        finish()
    }
}
