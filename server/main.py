"""
QuickCart dev backend (FastAPI): the same API the app's in-app fake backend simulates.

    cd server && uv run --with fastapi --with "uvicorn[standard]" uvicorn main:app --host 0.0.0.0 --port 8000

Then build the app with:  ./gradlew installDebug -Pquickcart.realBackend=true
(the emulator reaches this machine at 10.0.2.2:8000)
"""

import asyncio
import math
import random
import time

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel

app = FastAPI(title="QuickCart dev backend")

CATEGORIES = [
    {"id": "fruits-veg", "name": "Fruits & Vegetables", "emoji": "🥦"},
    {"id": "dairy", "name": "Dairy, Bread & Eggs", "emoji": "🥛"},
    {"id": "snacks", "name": "Snacks & Munchies", "emoji": "🍿"},
    {"id": "drinks", "name": "Cold Drinks & Juices", "emoji": "🧃"},
    {"id": "staples", "name": "Atta, Rice & Dal", "emoji": "🌾"},
    {"id": "home", "name": "Cleaning & Home", "emoji": "🧼"},
]


def _p(pid, name, cat, unit, price, mrp, emoji, description, in_stock=True):
    return {"id": pid, "name": name, "categoryId": cat, "unit": unit, "pricePaise": price * 100,
            "mrpPaise": mrp * 100, "emoji": emoji, "inStock": in_stock, "description": description}


PRODUCTS = [
    _p("banana", "Robusta Banana", "fruits-veg", "6 pcs", 42, 50, "🍌", "Naturally ripened, sweet and soft. Great for breakfast and shakes."),
    _p("tomato", "Hybrid Tomato", "fruits-veg", "500 g", 24, 30, "🍅", "Firm, juicy tomatoes for curries, salads and chutneys."),
    _p("onion", "Onion", "fruits-veg", "1 kg", 38, 45, "🧅", "Everyday red onions. Store in a cool, dry place."),
    _p("potato", "Potato", "fruits-veg", "1 kg", 32, 40, "🥔", "All-purpose potatoes for sabzi, fries and parathas."),
    _p("apple", "Shimla Apple", "fruits-veg", "4 pcs", 129, 160, "🍎", "Crisp and sweet apples from Himachal orchards."),
    _p("carrot", "Orange Carrot", "fruits-veg", "500 g", 35, 42, "🥕", "Crunchy carrots for halwa, salads and juices."),
    _p("grapes", "Green Grapes", "fruits-veg", "500 g", 79, 99, "🍇", "Seedless, sweet and washed. Keep refrigerated."),
    _p("mango", "Alphonso Mango", "fruits-veg", "2 pcs", 199, 240, "🥭", "The king of mangoes. Back in season soon.", in_stock=False),
    _p("milk", "Toned Milk", "dairy", "500 ml", 28, 28, "🥛", "Pasteurised toned milk, 3% fat. Boil before use."),
    _p("bread", "Whole Wheat Bread", "dairy", "400 g", 45, 50, "🍞", "Soft whole wheat bread, no maida. Best within 3 days."),
    _p("eggs", "Farm Eggs", "dairy", "6 pcs", 54, 60, "🥚", "Fresh brown eggs from cage-free farms."),
    _p("butter", "Salted Butter", "dairy", "100 g", 58, 60, "🧈", "Creamy salted butter for toast and parathas."),
    _p("paneer", "Fresh Paneer", "dairy", "200 g", 89, 99, "🧀", "Soft, fresh malai paneer. Use within 2 days."),
    _p("curd", "Fresh Curd", "dairy", "400 g", 35, 40, "🥣", "Thick set curd made from toned milk."),
    _p("chips", "Classic Salted Chips", "snacks", "52 g", 20, 20, "🥔", "Thin, crispy potato chips with a pinch of salt."),
    _p("popcorn", "Butter Popcorn", "snacks", "90 g", 45, 55, "🍿", "Ready-to-eat buttery popcorn for movie night."),
    _p("cookies", "Choco Chip Cookies", "snacks", "150 g", 55, 65, "🍪", "Crunchy cookies loaded with chocolate chips."),
    _p("chocolate", "Dark Chocolate", "snacks", "80 g", 99, 120, "🍫", "55% cocoa dark chocolate bar."),
    _p("peanuts", "Roasted Peanuts", "snacks", "200 g", 60, 75, "🥜", "Salted roasted peanuts, a protein-rich snack."),
    _p("noodles", "Instant Noodles", "snacks", "4 x 70 g", 56, 60, "🍜", "Ready in 2 minutes. Masala tastemaker inside."),
    _p("cola", "Cola", "drinks", "750 ml", 40, 45, "🥤", "Chilled fizzy cola. Best served cold."),
    _p("juice", "Mixed Fruit Juice", "drinks", "1 L", 99, 120, "🧃", "No added preservatives. Refrigerate after opening."),
    _p("coconut", "Tender Coconut Water", "drinks", "200 ml", 45, 50, "🥥", "Natural electrolytes, no added sugar."),
    _p("coffee", "Cold Coffee", "drinks", "180 ml", 35, 40, "☕", "Ready-to-drink cold coffee with real milk."),
    _p("greentea", "Green Tea Bags", "drinks", "25 pcs", 145, 175, "🍵", "Light, refreshing green tea. Zero calories."),
    _p("lemonade", "Lemon Soda", "drinks", "300 ml", 25, 30, "🍋", "Tangy lemon soda with a hint of mint."),
    _p("rice", "Basmati Rice", "staples", "1 kg", 115, 140, "🍚", "Long-grain aged basmati. Fluffy and fragrant."),
    _p("atta", "Whole Wheat Atta", "staples", "5 kg", 249, 290, "🌾", "Stone-ground chakki atta for soft rotis."),
    _p("dal", "Toor Dal", "staples", "1 kg", 165, 180, "🫘", "Unpolished arhar dal, high in protein."),
    _p("salt", "Iodised Salt", "staples", "1 kg", 25, 28, "🧂", "Free-flowing iodised salt."),
    _p("oil", "Sunflower Oil", "staples", "1 L", 155, 175, "🫗", "Refined sunflower oil, light for everyday cooking."),
    _p("sugar", "Sugar", "staples", "1 kg", 48, 55, "🍬", "Fine crystal sugar."),
    _p("soap", "Bathing Soap", "home", "3 x 100 g", 99, 120, "🧼", "Moisturising soap with glycerin."),
    _p("tissue", "Tissue Roll", "home", "4 rolls", 120, 150, "🧻", "Soft 2-ply kitchen and toilet rolls."),
    _p("sponge", "Scrub Sponge", "home", "3 pcs", 49, 60, "🧽", "Dual-sided scrub for utensils."),
    _p("detergent", "Liquid Detergent", "home", "1 L", 189, 230, "🧴", "For top and front load machines."),
    _p("dishwash", "Dishwash Gel", "home", "500 ml", 105, 120, "🫧", "Cuts grease fast, gentle on hands."),
    _p("garbagebag", "Garbage Bags", "home", "30 pcs", 89, 110, "🗑️", "Medium size, leak-proof bags."),
]
PRODUCT_IDS = {p["id"] for p in PRODUCTS}

# Same compressed timeline as OrderTimeline.kt: 10 "minutes" in 65 seconds.
PACKED_AT, OUT_AT, DELIVERED_AT, TOTAL_ETA_MIN = 10.0, 25.0, 65.0, 10

orders: dict[str, float] = {}          # server order id -> placed at (unix seconds)
by_client_id: dict[str, str] = {}      # idempotency: clientOrderId -> server order id


class OrderItem(BaseModel):
    productId: str
    quantity: int


class CreateOrder(BaseModel):
    clientOrderId: str
    items: list[OrderItem]
    address: str
    paymentMethod: str
    tipPaise: int = 0


def snapshot(order_id: str) -> dict:
    elapsed = time.time() - orders.setdefault(order_id, time.time())
    eta = max(1, math.ceil((DELIVERED_AT - elapsed) / (DELIVERED_AT / TOTAL_ETA_MIN)))
    if elapsed < PACKED_AT:
        status, progress, msg = "PLACED", 0.0, "Order received, picking your items"
    elif elapsed < OUT_AT:
        status, progress, msg = "PACKED", 0.0, "Packed and waiting for the rider"
    elif elapsed < DELIVERED_AT:
        status, progress, msg = "OUT_FOR_DELIVERY", (elapsed - OUT_AT) / (DELIVERED_AT - OUT_AT), "Rider is on the way"
    else:
        status, progress, msg, eta = "DELIVERED", 1.0, "Delivered. Enjoy!", 0
    return {"orderId": order_id, "status": status, "etaMinutes": eta, "riderProgress": round(progress, 3), "message": msg}


@app.get("/catalog")
async def catalog():
    return {"categories": CATEGORIES, "products": PRODUCTS}


@app.post("/orders", status_code=201)
async def create_order(body: CreateOrder):
    if not body.items or any(i.productId not in PRODUCT_IDS or i.quantity <= 0 for i in body.items):
        raise HTTPException(status_code=422, detail="invalid items")
    order_id = by_client_id.get(body.clientOrderId)
    if order_id is None:  # first time we see this clientOrderId -> create; retries get the same id
        order_id = f"QC{random.randint(100_000, 999_999)}"
        by_client_id[body.clientOrderId] = order_id
        orders[order_id] = time.time()
    return {"orderId": order_id, "status": "PLACED", "etaMinutes": TOTAL_ETA_MIN}


@app.websocket("/ws/orders/{order_id}")
async def track(websocket: WebSocket, order_id: str):
    await websocket.accept()
    try:
        while True:
            update = snapshot(order_id)
            await websocket.send_json(update)
            if update["status"] == "DELIVERED":
                await websocket.close(code=1000)
                return
            await asyncio.sleep(2)
    except WebSocketDisconnect:
        pass  # client left the screen; nothing to clean up
