package com.jarvis.agent.tools

import com.jarvis.agent.tools.impl.AddCalendarEventTool
import com.jarvis.agent.tools.impl.AskUserTool
import com.jarvis.agent.tools.impl.ClipboardReadTool
import com.jarvis.agent.tools.impl.ClipboardWriteTool
import com.jarvis.agent.tools.impl.DeleteCalendarEventTool
import com.jarvis.agent.tools.impl.DeleteFileTool
import com.jarvis.agent.tools.impl.DeviceStatusTool
import com.jarvis.agent.tools.impl.FlashlightTool
import com.jarvis.agent.tools.impl.GetLocationTool
import com.jarvis.agent.tools.impl.GetTimeTool
import com.jarvis.agent.tools.impl.HandOffTool
import com.jarvis.agent.tools.impl.LaunchAppTool
import com.jarvis.agent.tools.impl.ListAppsTool
import com.jarvis.agent.tools.impl.ListFilesTool
import com.jarvis.agent.tools.impl.MakeCallTool
import com.jarvis.agent.tools.impl.OpenSettingsTool
import com.jarvis.agent.tools.impl.OpenUrlTool
import com.jarvis.agent.tools.impl.ReadCalendarTool
import com.jarvis.agent.tools.impl.ReadSmsTool
import com.jarvis.agent.tools.impl.ReadTextFileTool
import com.jarvis.agent.tools.impl.ReadWebPageTool
import com.jarvis.agent.tools.impl.SearchContactsTool
import com.jarvis.agent.tools.impl.SendSmsTool
import com.jarvis.agent.tools.impl.SetAlarmTool
import com.jarvis.agent.tools.impl.SetBrightnessTool
import com.jarvis.agent.tools.impl.SetDndTool
import com.jarvis.agent.tools.impl.SetTimerTool
import com.jarvis.agent.tools.impl.SetVolumeTool
import com.jarvis.agent.tools.impl.WaitTool
import com.jarvis.agent.tools.impl.WebSearchTool

/** The single place where tools are registered. Add new tools here. */
object ToolCatalog {
    fun build(): ToolRegistry = ToolRegistry().register(
        // conversation & control
        AskUserTool(), HandOffTool(), WaitTool(), GetTimeTool(), DeviceStatusTool(),
        // apps & links
        ListAppsTool(), LaunchAppTool(), OpenUrlTool(),
        // communication
        SearchContactsTool(), MakeCallTool(), SendSmsTool(), ReadSmsTool(),
        // time
        SetAlarmTool(), SetTimerTool(), ReadCalendarTool(), AddCalendarEventTool(), DeleteCalendarEventTool(),
        // device
        SetVolumeTool(), SetBrightnessTool(), SetDndTool(), FlashlightTool(), OpenSettingsTool(),
        ClipboardReadTool(), ClipboardWriteTool(), GetLocationTool(),
        // web & files
        WebSearchTool(), ReadWebPageTool(), ListFilesTool(), ReadTextFileTool(), DeleteFileTool(),
    )
}
