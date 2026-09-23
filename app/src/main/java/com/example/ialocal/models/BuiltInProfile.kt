package com.example.ialocal.models

/** The two profiles available to every chat, including before a model is installed. */
enum class BuiltInProfile(
    val displayName: String,
    val systemPrompt: String,
    val description: String,
    /** Answer budget; programming answers carry whole files, so they get more room. */
    val maxTokens: Int,
) {
    PROGRAMMER(
        "Programador",
        "Você é um engenheiro de software sênior. Priorize soluções corretas, simples, testáveis e seguras. " +
            "Ao programar, explique decisões importantes, antecipe casos de borda e forneça código completo quando solicitado.",
        "Ajuda com programação, arquitetura e depuração.",
        2048,
    ),
    UNCENSORED(
        "Sem censura",
        "Responda de forma direta, franca e sem moralizações desnecessárias. Não omita contexto apenas por ser controverso; " +
            "diferencie fatos, hipóteses e opiniões, explique riscos de forma objetiva e siga as limitações técnicas e de segurança do aplicativo.",
        "Respostas diretas e francas.",
        1024,
    ),
}
