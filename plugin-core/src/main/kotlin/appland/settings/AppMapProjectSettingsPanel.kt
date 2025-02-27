package appland.settings

import appland.AppMapBundle
import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.util.text.Strings
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.RowLayout
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import javax.swing.JCheckBox
import javax.swing.JPanel

class AppMapProjectSettingsPanel {
    private lateinit var enableTelemetry: JCheckBox
    private lateinit var enableScanner: JCheckBox
    private lateinit var cliEnvironment: EnvironmentVariablesComponent
    private lateinit var openAIKey: JBTextField

    fun loadSettingsFrom(
        applicationSettings: AppMapApplicationSettings,
        secureApplicationSettings: AppMapSecureApplicationSettings
    ) {
        enableTelemetry.isSelected = applicationSettings.isEnableTelemetry
        enableScanner.isSelected = applicationSettings.isEnableScanner
        cliEnvironment.envs = applicationSettings.cliEnvironment
        cliEnvironment.isPassParentEnvs = applicationSettings.isCliPassParentEnv

        openAIKey.text = Strings.notNullize(secureApplicationSettings.openAIKey)
    }

    fun applySettingsTo(
        applicationSettings: AppMapApplicationSettings,
        secureApplicationSettings: AppMapSecureApplicationSettings,
        notify: Boolean,
    ) {
        applicationSettings.isEnableTelemetry = enableTelemetry.isSelected
        applicationSettings.isEnableScanner = enableScanner.isSelected
        applicationSettings.isCliPassParentEnv = cliEnvironment.isPassParentEnvs
        if (notify) {
            applicationSettings.setCliEnvironmentNotifying(cliEnvironment.envs)
        } else {
            applicationSettings.cliEnvironment = cliEnvironment.envs
        }

        secureApplicationSettings.openAIKey = Strings.nullize(openAIKey.text)
    }

    fun getMainPanel(): JPanel {
        cliEnvironment = EnvironmentVariablesComponent()
        cliEnvironment.labelLocation = BorderLayout.WEST

        return panel {
            row {
                enableTelemetry = checkBox(AppMapBundle.get("projectSettings.enableTelemetry.title")).component
            }
            group(AppMapBundle.get("projectSettings.appMapServices")) {
                row {
                    enableScanner = checkBox(AppMapBundle.get("projectSettings.enableScanner.title")).component
                }
                row(AppMapBundle.get("projectSettings.openAIKey.title")) {
                    openAIKey = textField().align(AlignX.FILL).component
                }.layout(RowLayout.INDEPENDENT)
                row {
                    cell(cliEnvironment).align(AlignX.FILL)
                }
            }
        }
    }
}