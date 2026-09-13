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
- **`targetSdk = 36`:** pode permanecer em 36 enquanto o projeto compila com API 37; ele não é a causa da exigência de AAR metadata observada.
- **Trigger `push` do workflow no `main`:** está funcional. O merge da correção disparou automaticamente um novo run do workflow Android.
- **Escopo da correção:** o diff revisado alterou somente `compileSdk` de 36 para 37 e a plataforma instalada pelo CI de `android-36` para `android-37`; nenhum outro parâmetro do app ou toolchain foi modificado.

### Itens excluídos das próximas revisões

Enquanto esses valores não forem alterados, não tratar como causa desta falha específica de AAR metadata:

- `minSdk = 33`;
- `targetSdk = 36`;
- mecanismo de trigger `push` do workflow no `main`.

> Observação: confirmar que esses pontos estão corretos não significa que todo o quadrante esteja correto. Apenas esses itens específicos foram encerrados e não serão reavaliados enquanto não houver mudança no código correspondente.
