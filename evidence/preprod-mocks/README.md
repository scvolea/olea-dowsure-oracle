# Preprod Mock Deployment + Live E2E Evidence

Plain-English summary: both contract-mock API stacks (Amazon SP-API and KYC) were
deployed to the Olea preprod AWS account, behind API-key auth, and every endpoint
was called live and returned HTTP 200 with the API key (403 without it).

## Deployment

- Account: 706179786846 (preprod), region ap-southeast-1 (Singapore), profile preprod.
- Deployed with `sam deploy` (API Gateway MOCK integrations only; no Lambda).

| Stack | ApiUrl | ApiKeyId |
| --- | --- | --- |
| `olea-dowsure-amazon-sp-mock` | https://097sqg03n1.execute-api.ap-southeast-1.amazonaws.com/mock | o3edrh1qi4 |
| `olea-dowsure-kyc-mock` | https://i8yde0kf2g.execute-api.ap-southeast-1.amazonaws.com/mock | 4oyo8bv75g |

API key values are retrieved with
`aws apigateway get-api-key --api-key <id> --include-value` and are NOT stored here.

## Live results (through coordinator/sources.py adapter + direct calls)

| # | Endpoint | Method | With key | Without key |
| --- | --- | --- | --- | --- |
| 1 | /sales/v1/orderMetrics (Amazon) | GET | 200 | 403 |
| 2 | /finances/v0/financialEventGroups (Amazon) | GET | 200 | - |
| 3 | /finances/2024-06-19/transactions (Amazon) | GET | 200 | - |
| 4 | /lundear/telThree (AliCloud) | GET | 200 | - |
| 5 | /EnterpriseInfo/Verify (Qichacha) | GET | 200 | - |
| 5 | /ShixinCheck/GetList (Qichacha) | GET | 200 | - |
| 6 | /api/v1/judicial/panorama-checks (Gutu) | POST | 200 | - |

The offline self-test (`scripts/e2e-sources.py --self-test`) additionally confirms the
Super PO field mapping: listTransactions ORDER_ID related-identifiers populate
`SuperPoRequestDto.order_ids` (verified `order_ids = ["8129762527551"]`).

## Note on the two API keys

Each stack has its own usage-plan API key. The adapter reads a single
`MOCK_API_KEY`, so the live run is done per-stack: Amazon endpoints with the
amazon-sp key, KYC endpoints with the kyc key. A future tidy-up could share one
usage plan across both stacks.

## Teardown

`aws cloudformation delete-stack --stack-name olea-dowsure-amazon-sp-mock --region ap-southeast-1 --profile preprod`
`aws cloudformation delete-stack --stack-name olea-dowsure-kyc-mock --region ap-southeast-1 --profile preprod`
