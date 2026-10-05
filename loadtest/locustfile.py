import os
import random
import uuid

import requests
from locust import HttpUser, constant, events, task
from locust.runners import MasterRunner, WorkerRunner

SYSTEM_ACCOUNT_ID = "00000000-0000-0000-0000-000000000001"
SCENARIO = os.getenv("SCENARIO", "spread")
ACCOUNT_COUNT = int(os.getenv("ACCOUNT_COUNT", "200"))
STARTING_BALANCE_MINOR = 10_000_000

accounts: list[str] = []


@events.test_start.add_listener
def create_funded_accounts(environment, **_):
    if isinstance(environment.runner, MasterRunner):
        return
    session = requests.Session()
    for i in range(ACCOUNT_COUNT):
        account = session.post(f"{environment.host}/accounts", json={"name": f"load-{SCENARIO}-{i}"}).json()
        funding = session.post(f"{environment.host}/transfers", json={
            "fromId": SYSTEM_ACCOUNT_ID,
            "toId": account["id"],
            "requestId": f"fund-{uuid.uuid4()}",
            "amountMinor": STARTING_BALANCE_MINOR,
        })
        funding.raise_for_status()
        accounts.append(account["id"])
    print(f"Created and funded {len(accounts)} accounts for scenario '{SCENARIO}'")


@events.test_stop.add_listener
def verify_ledger(environment, **_):
    if isinstance(environment.runner, WorkerRunner):
        return
    report = requests.get(f"{environment.host}/accounts/balances/verify").json()
    print(f"\nLedger verification after load: consistent={report['consistent']} "
          f"accountsChecked={report['accountsChecked']} mismatches={report['mismatchCount']} "
          f"ledgerNetMinor={report['ledgerNetMinor']}")


class LedgerUser(HttpUser):
    wait_time = constant(0)

    @task
    def transfer(self):
        if SCENARIO == "hot":
            sender, receiver = accounts[0], random.choice(accounts[1:])
        else:
            sender, receiver = random.sample(accounts, 2)

        payload = {
            "fromId": sender,
            "toId": receiver,
            "requestId": str(uuid.uuid4()),
            "amountMinor": random.randint(1, 100),
        }
        with self.client.post("/transfers", json=payload, name=f"POST /transfers [{SCENARIO}]",
                              catch_response=True) as response:
            if response.status_code in (200, 422):
                response.success()
            else:
                response.failure(f"HTTP {response.status_code}: {response.text[:200]}")
