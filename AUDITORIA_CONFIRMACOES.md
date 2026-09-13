# Auditoria — itens confirmados corretos

Este arquivo registra **somente pontos revisados duas vezes e confirmados como corretos**, para que sejam excluídos das próximas rodadas de investigação da falha de compilação.

## Rodada 1 — CI, Gradle e integração llama.cpp

- **AGP 9.4.0 + Gradle 9.6.0 + JDK 17:** combinação compatível. O AGP 9.4 exige Gradle 9.6.0 e JDK 17; portanto essa base não é a causa da falha.
- **`gradlew` customizado:** a ausência de `gradle/wrapper/gradle-wrapper.jar` não é defeito neste projeto. O `gradlew` foi substituído intencionalmente por um launcher próprio que baixa e executa o Gradle 9.6.0.
- **Java do workflow e do app:** o GitHub Actions prepara Java 17 e o módulo Android compila com `sourceCompatibility`/`targetCompatibility` 17. Não há divergência aqui.
- **Repositórios Gradle:** `pluginManagement` usa `google()`, `mavenCentral()` e `gradlePluginPortal()`; dependências usam `google()` e `mavenCentral()`. Isso cobre os plugins e dependências Maven declarados atualmente.
- **API pública do llama.cpp v0.4.0 usada pelo app:** `AiChat.getInferenceEngine`, `InferenceEngine.state`, `loadModel`, `setSystemPrompt`, `sendUserPrompt` e `cleanUp`, além dos estados referenciados por `LlamaCppRuntime`, existem no binding fixado em `v0.4.0` e têm assinaturas compatíveis com o código do app.
- **Patch de `prepare_llama_android.sh` contra o tag fixado:** os trechos que o script procura para aplicar o patch existem no `InferenceEngineImpl.kt` do `llama.cpp v0.4.0`, incluindo os pontos de `load`, `unload`, recuperação de `State.Error` e `destroy`. Portanto o formato atual do patch é compatível com o tag atualmente pinado.

### Itens excluídos das próximas revisões

Não revisar novamente, salvo alteração futura desses arquivos/versões:

- compatibilidade AGP 9.4.0 / Gradle 9.6.0 / JDK 17;
- ausência de `gradle-wrapper.jar`;
- alinhamento Java 17 entre CI e módulo Android;
- configuração básica de repositórios Gradle;
- existência/assinatura da API pública do binding `llama.cpp v0.4.0` usada por `LlamaCppRuntime`;
- correspondência textual atual do patch de `prepare_llama_android.sh` com o tag `v0.4.0`.

## Rodada 2 — AAR metadata / API Android

- **`minSdk = 33`:** não controla o nível de API usado para compilar dependências e não é a causa da falha `checkDebugAarMetadata` identificada nesta rodada.
- **`targetSdk = 36`:** pode permanecer em 36 enquanto o projeto compila com API 37.x; ele não é a causa da exigência de AAR metadata observada.
- **Trigger `push` do workflow no `main`:** está funcional. O merge da correção disparou automaticamente um novo run do workflow Android.

### Itens excluídos das próximas revisões

Enquanto esses valores não forem alterados, não tratar como causa desta falha específica de AAR metadata:

- `minSdk = 33`;
- `targetSdk = 36`;
- mecanismo de trigger `push` do workflow no `main`.

## Rodada 3 — Android API 37.0 no toolchain

- **SDK Build Tools 36.0.0:** é a versão padrão suportada pelo AGP 9.4.0; não precisa ser elevado para 37.0.0 apenas porque o `compileSdk` é API 37.0.
- **AGP 9.4.0 e API 37.0:** o AGP 9.4 suporta oficialmente API 37.0, portanto não é necessário trocar o plugin para corrigir a instalação da plataforma.
- **Forma do `compileSdk` para API menor:** o AGP atual oferece a DSL `compileSdk { version = release(...) { minorApiLevel = ... } }`, adequada para declarar explicitamente API 37.0.

### Itens excluídos das próximas revisões

- necessidade de atualizar Build Tools apenas por causa da API 37.0;
- compatibilidade do AGP 9.4.0 com API 37.0;
- existência da DSL de API menor para `compileSdk`.

## Rodada 4 — revisão cruzada API 37 / llama.cpp / KSP

- **Plataforma Android 37.0 no runner:** a etapa `Native toolchain` do run #111 concluiu com sucesso usando `platforms;android-37.0`, confirmando que esse é um pacote válido no ambiente atual.
- **Duas plataformas são intencionais:** o app principal compila contra API 37.0, enquanto o binding fixado `llama.cpp v0.4.0` declara `compileSdk = 36`. O workflow agora instala explicitamente `platforms;android-36` e `platforms;android-37.0`, sem depender da imagem do runner trazer API 36 implicitamente.
- **NDK e CMake do binding:** o `llama.cpp v0.4.0` fixa NDK `29.0.13113456` e CMake `3.31.6`; são exatamente as versões instaladas pelo workflow.
- **Compose estável não exige API 37.1:** a linha Compose 1.12 usada pela BOM estável `2026.08.00` exige API 37. A exigência 37.1 aparece nas linhas alpha posteriores, que não estão declaradas neste projeto.
- **KSP com AGP 9:** o projeto usa KSP `2.3.11`, acima do mínimo `2.3.6` recomendado para migração AGP 9. Room `2.8.4` é processado via KSP e o código não usa `@Parcelize` em entidades, portanto o bug conhecido dessa combinação específica não se aplica ao código atual.
- **JVM target com Kotlin integrado:** com Kotlin integrado do AGP 9, o alvo Kotlin herda `android.compileOptions.targetCompatibility`; o módulo já define Java 17, portanto não é necessário adicionar uma segunda configuração `kotlinOptions.jvmTarget`.

### Itens excluídos das próximas revisões

Enquanto as versões e dependências permanecerem iguais:

- validade de `platforms;android-37.0` no runner;
- necessidade de API 37.1 para a BOM estável atual;
- compatibilidade básica de KSP `2.3.11` com AGP 9;
- alinhamento NDK/CMake do workflow com `llama.cpp v0.4.0`;
- divergência de JVM target Java/Kotlin no módulo principal.

> Observação: confirmar que esses pontos estão corretos não significa que todo o quadrante esteja correto. Apenas esses itens específicos foram encerrados e não serão reavaliados enquanto não houver mudança no código correspondente.
