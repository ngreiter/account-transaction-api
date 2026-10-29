-- Gera dados sintéticos para desenvolvimento e demonstração.
-- Execute somente em uma base de desenvolvimento. Cada execução cria um novo lote.
-- Documentos passam nos dígitos verificadores, mas não são dados de produção.

BEGIN;
SET LOCAL TIME ZONE 'America/Sao_Paulo';
-- Inicializa o schema temporário usado pelas funções auxiliares abaixo.
CREATE TEMPORARY TABLE seed_script_temp_schema_ready (id INTEGER) ON COMMIT DROP;

CREATE OR REPLACE FUNCTION pg_temp.demo_seed_cpf()
RETURNS TEXT
LANGUAGE plpgsql
AS $function$
DECLARE
    v_base TEXT;
    v_sum INTEGER;
    v_remainder INTEGER;
    v_first_digit INTEGER;
    v_second_digit INTEGER;
    v_index INTEGER;
BEGIN
    LOOP
        v_base := LPAD(FLOOR(RANDOM() * 1000000000)::BIGINT::TEXT, 9, '0');
        EXIT WHEN v_base <> REPEAT(SUBSTRING(v_base, 1, 1), 9);
    END LOOP;

    v_sum := 0;
    FOR v_index IN 1..9 LOOP
        v_sum := v_sum + SUBSTRING(v_base, v_index, 1)::INTEGER * (11 - v_index);
    END LOOP;
    v_remainder := v_sum % 11;
    v_first_digit := CASE WHEN v_remainder < 2 THEN 0 ELSE 11 - v_remainder END;

    v_sum := 0;
    FOR v_index IN 1..10 LOOP
        v_sum := v_sum + SUBSTRING(v_base || v_first_digit::TEXT, v_index, 1)::INTEGER * (12 - v_index);
    END LOOP;
    v_remainder := v_sum % 11;
    v_second_digit := CASE WHEN v_remainder < 2 THEN 0 ELSE 11 - v_remainder END;

    RETURN v_base || v_first_digit::TEXT || v_second_digit::TEXT;
END;
$function$;

CREATE OR REPLACE FUNCTION pg_temp.demo_seed_cnpj()
RETURNS TEXT
LANGUAGE plpgsql
AS $function$
DECLARE
    v_base TEXT;
    v_sum INTEGER;
    v_remainder INTEGER;
    v_first_digit INTEGER;
    v_second_digit INTEGER;
    v_index INTEGER;
    v_first_weights INTEGER[] := ARRAY[5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
    v_second_weights INTEGER[] := ARRAY[6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
BEGIN
    LOOP
        -- Raiz aleatória de oito dígitos e estabelecimento 0001.
        v_base := LPAD(FLOOR(RANDOM() * 100000000)::BIGINT::TEXT, 8, '0') || '0001';
        EXIT WHEN v_base <> REPEAT(SUBSTRING(v_base, 1, 1), 12);
    END LOOP;

    v_sum := 0;
    FOR v_index IN 1..12 LOOP
        v_sum := v_sum + SUBSTRING(v_base, v_index, 1)::INTEGER * v_first_weights[v_index];
    END LOOP;
    v_remainder := v_sum % 11;
    v_first_digit := CASE WHEN v_remainder < 2 THEN 0 ELSE 11 - v_remainder END;

    v_sum := 0;
    FOR v_index IN 1..13 LOOP
        v_sum := v_sum + SUBSTRING(v_base || v_first_digit::TEXT, v_index, 1)::INTEGER
            * v_second_weights[v_index];
    END LOOP;
    v_remainder := v_sum % 11;
    v_second_digit := CASE WHEN v_remainder < 2 THEN 0 ELSE 11 - v_remainder END;

    RETURN v_base || v_first_digit::TEXT || v_second_digit::TEXT;
END;
$function$;

CREATE OR REPLACE FUNCTION pg_temp.demo_seed_account_number()
RETURNS TEXT
LANGUAGE plpgsql
AS $function$
DECLARE
    v_base TEXT;
    v_sum INTEGER := 0;
    v_weight INTEGER := 2;
    v_digit INTEGER;
    v_index INTEGER;
BEGIN
    v_base := LPAD(FLOOR(RANDOM() * 100000000)::BIGINT::TEXT, 8, '0');
    FOR v_index IN REVERSE 8..1 LOOP
        v_sum := v_sum + SUBSTRING(v_base, v_index, 1)::INTEGER * v_weight;
        v_weight := CASE WHEN v_weight = 9 THEN 2 ELSE v_weight + 1 END;
    END LOOP;

    v_digit := 11 - (v_sum % 11);
    IF v_digit >= 10 THEN
        v_digit := 0;
    END IF;

    RETURN v_base || '-' || v_digit::TEXT;
END;
$function$;

DO $seed$
DECLARE
    v_run_started TIMESTAMPTZ := CLOCK_TIMESTAMP();
    v_month_start TIMESTAMPTZ;
    v_account_numbers TEXT[] := ARRAY[]::TEXT[];
    v_account_number TEXT;
    v_document TEXT;
    v_financial_id UUID;
    v_transaction_type TEXT;
    v_movement_type TEXT;
    v_description TEXT;
    v_amount NUMERIC(19, 2);
    v_balance_before NUMERIC(19, 2);
    v_balance_after NUMERIC(19, 2);
    v_current_balance NUMERIC(19, 2);
    v_destination_balance NUMERIC(19, 2);
    v_posted_at TIMESTAMPTZ;
    v_account_index INTEGER;
    v_destination_index INTEGER;
    v_steps INTEGER;
    v_financial_count INTEGER := 0;
    v_movement_count INTEGER := 0;
    v_transfer_count CONSTANT INTEGER := 100;
    v_index INTEGER;
    v_step INTEGER;
BEGIN
    v_month_start := DATE_TRUNC('month', v_run_started);

    -- Cria 100 contas: 80 CPFs e 20 CNPJs, sempre sem pontuação.
    FOR v_index IN 1..100 LOOP
        LOOP
            v_account_number := pg_temp.demo_seed_account_number();
            v_document := CASE
                WHEN v_index <= 80 THEN pg_temp.demo_seed_cpf()
                ELSE pg_temp.demo_seed_cnpj()
            END;

            EXIT WHEN NOT EXISTS (
                SELECT 1
                FROM account
                WHERE account_number = v_account_number OR document = v_document
            );
        END LOOP;

        INSERT INTO account (account_number, document, balance, created_at)
        VALUES (v_account_number, v_document, 0.00, v_run_started - INTERVAL '24 months');

        v_account_numbers := ARRAY_APPEND(v_account_numbers, v_account_number);
    END LOOP;

    -- Todas as contas recebem histórico. As primeiras dez têm mais de 20 lançamentos
    -- para facilitar a demonstração de páginas completas e da última página.
    FOR v_account_index IN 1..100 LOOP
        v_steps := CASE
            WHEN v_account_index <= 10 THEN 26 + FLOOR(RANDOM() * 10)::INTEGER
            ELSE 7 + FLOOR(RANDOM() * 9)::INTEGER
        END;
        v_current_balance := 0.00;
        v_account_number := v_account_numbers[v_account_index];

        FOR v_step IN 1..v_steps LOOP
            v_posted_at := (v_run_started - INTERVAL '24 months')
                + INTERVAL '685 days' * (v_step::DOUBLE PRECISION / v_steps)
                + RANDOM() * INTERVAL '30 minutes';

            IF v_step = 1 OR RANDOM() < 0.58 THEN
                v_movement_type := 'CREDIT';
                v_transaction_type := 'DEPOSIT';
                IF v_step = 1 THEN
                    v_amount := ROUND((500 + RANDOM() * 2500)::NUMERIC, 2);
                    v_description := '[DEMO-SEED] Depósito inicial';
                ELSE
                    v_amount := ROUND((50 + RANDOM() * 1450)::NUMERIC, 2);
                    v_description := CASE FLOOR(RANDOM() * 3)::INTEGER
                        WHEN 0 THEN '[DEMO-SEED] Depósito via PIX'
                        WHEN 1 THEN '[DEMO-SEED] Crédito de salário'
                        ELSE '[DEMO-SEED] Depósito em dinheiro'
                    END;
                END IF;
            ELSE
                v_movement_type := 'DEBIT';
                v_transaction_type := 'WITHDRAWAL';
                v_amount := ROUND((20 + RANDOM() * 480)::NUMERIC, 2);
                v_description := CASE FLOOR(RANDOM() * 3)::INTEGER
                    WHEN 0 THEN '[DEMO-SEED] Saque em terminal'
                    WHEN 1 THEN '[DEMO-SEED] Pagamento de contas'
                    ELSE '[DEMO-SEED] Compra no débito'
                END;

                -- Mantém margem positiva para saques e transferências posteriores.
                IF v_current_balance - v_amount < 200.00 THEN
                    v_movement_type := 'CREDIT';
                    v_transaction_type := 'DEPOSIT';
                    v_amount := ROUND((50 + RANDOM() * 1450)::NUMERIC, 2);
                    v_description := '[DEMO-SEED] Depósito via PIX';
                END IF;
            END IF;

            v_balance_before := v_current_balance;
            IF v_movement_type = 'CREDIT' THEN
                v_balance_after := v_balance_before + v_amount;
            ELSE
                v_balance_after := v_balance_before - v_amount;
            END IF;

            v_financial_id := GEN_RANDOM_UUID();
            INSERT INTO financial_transaction (id, transaction_type, amount, created_at)
            VALUES (v_financial_id, v_transaction_type, v_amount, v_posted_at);

            INSERT INTO account_transaction (
                id, account_number, financial_transaction_id, movement_type,
                amount, balance_before, balance_after, description, posted_at
            ) VALUES (
                GEN_RANDOM_UUID(), v_account_number, v_financial_id, v_movement_type,
                v_amount, v_balance_before, v_balance_after, v_description, v_posted_at
            );

            UPDATE account SET balance = v_balance_after WHERE account_number = v_account_number;
            v_current_balance := v_balance_after;
            v_financial_count := v_financial_count + 1;
            v_movement_count := v_movement_count + 1;
        END LOOP;
    END LOOP;

    -- Cria um ciclo de 100 transferências. Cada transferência tem uma transação
    -- financeira e dois movimentos: débito na origem e crédito no destino.
    FOR v_step IN 1..v_transfer_count LOOP
        v_account_index := v_step;
        v_destination_index := CASE WHEN v_step = 100 THEN 1 ELSE v_step + 1 END;
        v_account_number := v_account_numbers[v_account_index];
        v_balance_before := NULL;
        v_destination_balance := NULL;

        SELECT balance INTO v_balance_before
        FROM account WHERE account_number = v_account_number;

        SELECT balance INTO v_destination_balance
        FROM account WHERE account_number = v_account_numbers[v_destination_index];

        v_amount := ROUND((20 + RANDOM() * 180)::NUMERIC, 2);
        v_balance_after := v_balance_before - v_amount;
        v_posted_at := v_month_start
            + (v_run_started - v_month_start) * (v_step::DOUBLE PRECISION / (v_transfer_count + 1));
        v_financial_id := GEN_RANDOM_UUID();

        INSERT INTO financial_transaction (id, transaction_type, amount, created_at)
        VALUES (v_financial_id, 'TRANSFER', v_amount, v_posted_at);

        INSERT INTO account_transaction (
            id, account_number, financial_transaction_id, movement_type,
            amount, balance_before, balance_after, description, posted_at
        ) VALUES (
            GEN_RANDOM_UUID(), v_account_numbers[v_account_index], v_financial_id, 'DEBIT',
            v_amount, v_balance_before, v_balance_after,
            '[DEMO-SEED] Transferência enviada para ' || v_account_numbers[v_destination_index], v_posted_at
        );

        INSERT INTO account_transaction (
            id, account_number, financial_transaction_id, movement_type,
            amount, balance_before, balance_after, description, posted_at
        ) VALUES (
            GEN_RANDOM_UUID(), v_account_numbers[v_destination_index], v_financial_id, 'CREDIT',
            v_amount, v_destination_balance, v_destination_balance + v_amount,
            '[DEMO-SEED] Transferência recebida de ' || v_account_numbers[v_account_index], v_posted_at
        );

        UPDATE account
        SET balance = v_balance_after
        WHERE account_number = v_account_numbers[v_account_index];

        UPDATE account
        SET balance = v_destination_balance + v_amount
        WHERE account_number = v_account_numbers[v_destination_index];

        v_financial_count := v_financial_count + 1;
        v_movement_count := v_movement_count + 2;
    END LOOP;

    RAISE NOTICE 'Dados criados: 100 contas, % transações financeiras e % movimentos de conta.',
        v_financial_count, v_movement_count;
END;
$seed$;

DROP FUNCTION pg_temp.demo_seed_cpf();
DROP FUNCTION pg_temp.demo_seed_cnpj();
DROP FUNCTION pg_temp.demo_seed_account_number();

COMMIT;

-- Contas geradas com maior histórico, úteis para testar paginação do extrato.
SELECT a.account_number, a.balance, COUNT(t.id) AS movement_count
FROM public.account a
JOIN public.account_transaction t ON t.account_number = a.account_number
WHERE t.description LIKE '[DEMO-SEED]%'
GROUP BY a.account_number, a.balance
HAVING COUNT(t.id) > 20
ORDER BY movement_count DESC, a.account_number
LIMIT 10;
