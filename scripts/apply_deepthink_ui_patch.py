from pathlib import Path

path = Path("app/src/main/java/com/example/ialocal/ui/chat/ChatScreen.kt")
text = path.read_text()

old = """    val audioRecorder = remember { AudioRecorder(context) }\n    var isRecording by remember { mutableStateOf(false) }\n"""
new = """    val audioRecorder = remember { AudioRecorder(context) }\n    val deepThinkStore = remember(context) {\n        com.example.ialocal.models.DeepThinkStore(context.applicationContext)\n    }\n    var isRecording by remember { mutableStateOf(false) }\n"""
assert old in text, "audioRecorder anchor not found"
text = text.replace(old, new, 1)

old = """    if (agentSettingsOpen && selectedAgent != null) {\n        AgentSettingsDialog(\n            agent = selectedAgent,\n            onDismiss = { agentSettingsOpen = false },\n            onSave = {\n                viewModel.updateAgent(it)\n                agentSettingsOpen = false\n            },\n        )\n    }\n"""
new = """    if (agentSettingsOpen && selectedAgent != null) {\n        DeepThinkAgentSettingsDialog(\n            agent = selectedAgent,\n            model = selectedModel,\n            store = deepThinkStore,\n            onDismiss = { agentSettingsOpen = false },\n            onSave = { updatedAgent, level ->\n                viewModel.updateAgent(updatedAgent)\n                deepThinkStore.setLevel(updatedAgent.id, level)\n                agentSettingsOpen = false\n            },\n        )\n    }\n"""
assert old in text, "agent settings block not found"
text = text.replace(old, new, 1)

old = """    if (createAgentOpen) {\n        CreateAgentDialog(\n            onDismiss = { createAgentOpen = false },\n            onSave = { name, prompt, temperature ->\n                val model = selectedModel\n                if (model == null) {\n                    scope.launch { snackbar.showSnackbar(\"Selecione um modelo offline verificado primeiro.\") }\n                } else {\n                    viewModel.createAgentProfile(\n                        modelId = model.id,\n                        name = name,\n                        systemPrompt = prompt,\n                        temperature = temperature,\n                    ) { created ->\n                        createAgentOpen = false\n                        pendingDefaultAgent = created\n                    }\n                }\n            },\n        )\n    }\n"""
new = """    if (createAgentOpen) {\n        DeepThinkCreateAgentDialog(\n            model = selectedModel,\n            onDismiss = { createAgentOpen = false },\n            onSave = { name, prompt, temperature, deepThinkLevel ->\n                val model = selectedModel\n                if (model == null) {\n                    scope.launch { snackbar.showSnackbar(\"Selecione um modelo offline verificado primeiro.\") }\n                } else {\n                    viewModel.createAgentProfile(\n                        modelId = model.id,\n                        name = name,\n                        systemPrompt = prompt,\n                        temperature = temperature,\n                    ) { created ->\n                        deepThinkStore.setLevel(created.id, deepThinkLevel)\n                        createAgentOpen = false\n                        pendingDefaultAgent = created\n                    }\n                }\n            },\n        )\n    }\n"""
assert old in text, "create agent block not found"
text = text.replace(old, new, 1)

path.write_text(text)
