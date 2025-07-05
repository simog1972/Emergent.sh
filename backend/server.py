from fastapi import FastAPI, APIRouter, HTTPException, Depends, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from dotenv import load_dotenv
from starlette.middleware.cors import CORSMiddleware
from motor.motor_asyncio import AsyncIOMotorClient
import os
import logging
from pathlib import Path
from pydantic import BaseModel, Field
from typing import List, Optional
import uuid
from datetime import datetime, timedelta
import bcrypt
import jwt
from passlib.context import CryptContext


ROOT_DIR = Path(__file__).parent
load_dotenv(ROOT_DIR / '.env')

# MongoDB connection
mongo_url = os.environ['MONGO_URL']
client = AsyncIOMotorClient(mongo_url)
db = client[os.environ['DB_NAME']]

# Security
SECRET_KEY = os.environ.get('SECRET_KEY', 'your-secret-key-here-change-in-production')
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_MINUTES = 30

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
security = HTTPBearer()

# Create the main app without a prefix
app = FastAPI()

# Create a router with the /api prefix
api_router = APIRouter(prefix="/api")


# Define Models
class BOMItem(BaseModel):
    item_id: str
    item_name: str
    quantity: int
    delivery_time: str
    status: str = "pending"

class Order(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    customer_id: str
    order_id: str
    forecast_delivery: str
    status: str = "active"
    bom_items: List[BOMItem]
    created_at: datetime = Field(default_factory=datetime.utcnow)

class OrderCreate(BaseModel):
    order_id: str
    forecast_delivery: str
    bom_items: List[BOMItem]

class User(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    username: str
    customer_id: str
    password_hash: str
    created_at: datetime = Field(default_factory=datetime.utcnow)

class UserCreate(BaseModel):
    username: str
    password: str

class UserLogin(BaseModel):
    username: str
    password: str

class Token(BaseModel):
    access_token: str
    token_type: str

class UserResponse(BaseModel):
    id: str
    username: str
    customer_id: str

# Security functions
def verify_password(plain_password, hashed_password):
    return pwd_context.verify(plain_password, hashed_password)

def get_password_hash(password):
    return pwd_context.hash(password)

def create_access_token(data: dict, expires_delta: Optional[timedelta] = None):
    to_encode = data.copy()
    if expires_delta:
        expire = datetime.utcnow() + expires_delta
    else:
        expire = datetime.utcnow() + timedelta(minutes=15)
    to_encode.update({"exp": expire})
    encoded_jwt = jwt.encode(to_encode, SECRET_KEY, algorithm=ALGORITHM)
    return encoded_jwt

async def get_current_user(credentials: HTTPAuthorizationCredentials = Depends(security)):
    credentials_exception = HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="Could not validate credentials",
        headers={"WWW-Authenticate": "Bearer"},
    )
    try:
        payload = jwt.decode(credentials.credentials, SECRET_KEY, algorithms=[ALGORITHM])
        username: str = payload.get("sub")
        if username is None:
            raise credentials_exception
    except jwt.PyJWTError:
        raise credentials_exception
    user = await db.users.find_one({"username": username})
    if user is None:
        raise credentials_exception
    return User(**user)

# Authentication endpoints
@api_router.post("/register", response_model=UserResponse)
async def register(user_data: UserCreate):
    # Check if user already exists
    existing_user = await db.users.find_one({"username": user_data.username})
    if existing_user:
        raise HTTPException(
            status_code=400,
            detail="Username already registered"
        )
    
    # Create new user
    hashed_password = get_password_hash(user_data.password)
    user = User(
        username=user_data.username,
        customer_id=f"CUST_{str(uuid.uuid4())[:8].upper()}",
        password_hash=hashed_password
    )
    
    await db.users.insert_one(user.dict())
    return UserResponse(**user.dict())

@api_router.post("/login", response_model=Token)
async def login(user_credentials: UserLogin):
    user = await db.users.find_one({"username": user_credentials.username})
    if not user or not verify_password(user_credentials.password, user["password_hash"]):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Incorrect username or password",
            headers={"WWW-Authenticate": "Bearer"},
        )
    
    access_token_expires = timedelta(minutes=ACCESS_TOKEN_EXPIRE_MINUTES)
    access_token = create_access_token(
        data={"sub": user["username"]}, expires_delta=access_token_expires
    )
    return {"access_token": access_token, "token_type": "bearer"}

@api_router.get("/me", response_model=UserResponse)
async def get_current_user_info(current_user: User = Depends(get_current_user)):
    return UserResponse(**current_user.dict())

# Order endpoints
@api_router.get("/orders", response_model=List[Order])
async def get_orders(current_user: User = Depends(get_current_user)):
    orders = await db.orders.find({"customer_id": current_user.customer_id}).to_list(1000)
    return [Order(**order) for order in orders]

@api_router.get("/orders/{order_id}", response_model=Order)
async def get_order(order_id: str, current_user: User = Depends(get_current_user)):
    order = await db.orders.find_one({"order_id": order_id, "customer_id": current_user.customer_id})
    if not order:
        raise HTTPException(status_code=404, detail="Order not found")
    return Order(**order)

@api_router.post("/orders", response_model=Order)
async def create_order(order_data: OrderCreate, current_user: User = Depends(get_current_user)):
    order = Order(
        customer_id=current_user.customer_id,
        **order_data.dict()
    )
    await db.orders.insert_one(order.dict())
    return order

# Initialize sample data
@api_router.post("/init-sample-data")
async def init_sample_data():
    # Create sample users if they don't exist
    sample_users = [
        {"username": "customer1", "password": "password123"},
        {"username": "customer2", "password": "password456"}
    ]
    
    for user_data in sample_users:
        existing_user = await db.users.find_one({"username": user_data["username"]})
        if not existing_user:
            hashed_password = get_password_hash(user_data["password"])
            user = User(
                username=user_data["username"],
                customer_id=f"CUST_{str(uuid.uuid4())[:8].upper()}",
                password_hash=hashed_password
            )
            await db.users.insert_one(user.dict())
    
    # Create sample orders
    users = await db.users.find().to_list(10)
    
    for user in users:
        # Check if this user already has orders
        existing_orders = await db.orders.find({"customer_id": user["customer_id"]}).to_list(1)
        if existing_orders:
            continue
            
        sample_orders = [
            {
                "order_id": f"ORD-{str(uuid.uuid4())[:8].upper()}",
                "forecast_delivery": "2024-02-15",
                "bom_items": [
                    {"item_id": "ITEM001", "item_name": "Steel Plate", "quantity": 5, "delivery_time": "2024-02-10", "status": "ready"},
                    {"item_id": "ITEM002", "item_name": "Aluminum Rod", "quantity": 3, "delivery_time": "2024-02-12", "status": "pending"},
                    {"item_id": "ITEM003", "item_name": "Copper Wire", "quantity": 10, "delivery_time": "2024-02-14", "status": "in_transit"}
                ]
            },
            {
                "order_id": f"ORD-{str(uuid.uuid4())[:8].upper()}",
                "forecast_delivery": "2024-02-20",
                "bom_items": [
                    {"item_id": "ITEM004", "item_name": "Plastic Housing", "quantity": 2, "delivery_time": "2024-02-18", "status": "ready"},
                    {"item_id": "ITEM005", "item_name": "Electronic Board", "quantity": 1, "delivery_time": "2024-02-19", "status": "pending"}
                ]
            },
            {
                "order_id": f"ORD-{str(uuid.uuid4())[:8].upper()}",
                "forecast_delivery": "2024-02-25",
                "bom_items": [
                    {"item_id": "ITEM006", "item_name": "Screws Set", "quantity": 50, "delivery_time": "2024-02-22", "status": "ready"},
                    {"item_id": "ITEM007", "item_name": "Gasket", "quantity": 8, "delivery_time": "2024-02-23", "status": "in_transit"},
                    {"item_id": "ITEM008", "item_name": "Labels", "quantity": 20, "delivery_time": "2024-02-24", "status": "pending"}
                ]
            }
        ]
        
        for order_data in sample_orders:
            order = Order(
                customer_id=user["customer_id"],
                **order_data
            )
            await db.orders.insert_one(order.dict())
    
    return {"message": "Sample data initialized successfully"}

# Include the router in the main app
app.include_router(api_router)

app.add_middleware(
    CORSMiddleware,
    allow_credentials=True,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

@app.on_event("shutdown")
async def shutdown_db_client():
    client.close()