package com.pluginslab.agentbridge.agent

import android.os.Bundle
import android.view.WindowManager

class CompactConversationActivity: PanelActivity() {
    override fun onCreate(state: Bundle?) {
        intent.putExtra("compact",true)
        super.onCreate(state)
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,(resources.displayMetrics.heightPixels*0.82).toInt())
        window.setDimAmount(0.3f)
    }
}
