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

A primeira configuração prevista roda a aplicação pelo Eclipse e conecta a um banco PostgreSQL dedicado no servidor S20. O banco e o usuário do desafio devem ser separados dos dados da Unifisio. Docker Compose pode ser acrescentado depois para facilitar uma execução isolada; não é necessário para começar.

Quando a configuração do banco estiver pronta, as credenciais serão fornecidas por variáveis de ambiente ou configuração local ignorada pelo Git. Não coloque senhas, chaves ou dados pessoais reais no repositório.

O projeto foi gerado com Maven Wrapper. Após implementar a configuração, os comandos previstos serão:

```powershell
.\mvnw.cmd spring-boot:run
```

Para compilar e executar os testes durante o desenvolvimento:

```powershell
.\mvnw.cmd verify
```

Ainda não há endpoints funcionais. Consulte o contrato como especificação planejada, não como uma API já executável.
