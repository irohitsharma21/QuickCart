"""uv run --with fastapi --with httpx --with pytest pytest -q"""

from fastapi.testclient import TestClient

from main import app

client = TestClient(app)


def test_catalog():
    body = client.get("/catalog").json()
    assert len(body["categories"]) == 6
    assert len(body["products"]) == 38


def test_create_order_is_idempotent():
    req = {"clientOrderId": "abc", "items": [{"productId": "milk", "quantity": 2}], "address": "x", "paymentMethod": "UPI"}
    first = client.post("/orders", json=req)
    second = client.post("/orders", json=req)
    assert first.status_code == 201
    assert first.json()["orderId"] == second.json()["orderId"]


def test_invalid_items_rejected():
    req = {"clientOrderId": "bad", "items": [{"productId": "nope", "quantity": 1}], "address": "x", "paymentMethod": "UPI"}
    assert client.post("/orders", json=req).status_code == 422


def test_websocket_pushes_status():
    order_id = client.post(
        "/orders",
        json={"clientOrderId": "ws", "items": [{"productId": "milk", "quantity": 1}], "address": "x", "paymentMethod": "UPI"},
    ).json()["orderId"]
    with client.websocket_connect(f"/ws/orders/{order_id}") as ws:
        update = ws.receive_json()
        assert update["orderId"] == order_id
        assert update["status"] == "PLACED"
