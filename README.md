# Account Transaction API

API REST para o desafio técnico de contas correntes: abertura de conta, depósitos, saques, transferências e consulta de extrato.

> **Status:** repositório recém-criado. Neste momento contém o esqueleto gerado pelo Spring Tools; as regras e o contrato abaixo estão planejados e ainda serão implementados.

## Stack planejada

- Java 21
- Spring Boot 4.1.2
- Spring Web MVC, Spring Data JPA / Hibernate e Bean Validation
- PostgreSQL e Flyway
- Maven

## Documentação

- [Decisões e arquitetura](docs/DECISOES_E_ARQUITETURA.md): escopo, regras, modelo de dados, concorrência, idempotência e privacidade.
- [Contrato da API](docs/CONTRATO_API.md): endpoints, exemplos e respostas de erro planejadas.
- [Roteiro de apresentação](docs/APRESENTACAO.md): resumo da solução e pontos para explicar na entrevista.

## Ambiente de desenvolvimento

Requisitos: Java 21, PostgreSQL e Maven Wrapper (incluído no repositório). A aplicação conecta ao database `account_transaction` no PostgreSQL do servidor S20. A migração Flyway `V1` é executada automaticamente na inicialização; o Hibernate valida o esquema e não o altera.

A conexão exige estas variáveis de ambiente, sem valores padrão:

| Variável | Valor |
|---|---|
| `DB_URL` | URL JDBC apontando para o database `account_transaction` no PostgreSQL do S20, no formato `jdbc:postgresql://<host-do-S20>:5432/account_transaction` |
| `DB_USERNAME` | `account_transaction_app` |
| `DB_PASSWORD` | Senha fornecida para o usuário do banco; não a salve no repositório. |

No PowerShell, configure as variáveis na sessão local antes de iniciar. Substitua `<host-do-S20>` pelo endereço fornecido para o ambiente, sem registrar esse endereço no repositório. Informe localmente a senha correspondente:

```powershell
$env:DB_URL = "jdbc:postgresql://<host-do-S20>:5432/account_transaction"
$env:DB_USERNAME = "account_transaction_app"
$env:DB_PASSWORD = "<senha-local>"
.\mvnw.cmd spring-boot:run
```

No Eclipse, abra **Run Configurations**, selecione a configuração da aplicação e adicione `DB_URL`, `DB_USERNAME` e `DB_PASSWORD` em **Environment**. Use o mesmo database e usuário descritos acima. Não coloque senha nem IP/endereço da rede em arquivos versionados; configure-os apenas no ambiente local.

Para compilar e executar a verificação do projeto:

```powershell
.\mvnw.cmd verify
```

O contrato HTTP permanece planejado; os endpoints ainda não estão implementados.
