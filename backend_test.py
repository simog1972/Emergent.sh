#!/usr/bin/env python3
import requests
import json
import sys
from typing import Dict, Any, Optional, List, Tuple
import time

# Get the backend URL from the frontend .env file
BACKEND_URL = "https://5eb7963b-c42f-4fff-8db8-2a32c2ecc215.preview.emergentagent.com"
API_URL = f"{BACKEND_URL}/api"

# Test credentials
TEST_CREDENTIALS = {
    "valid_user1": {"username": "customer1", "password": "password123"},
    "valid_user2": {"username": "customer2", "password": "password456"},
    "invalid_user": {"username": "nonexistent", "password": "wrongpassword"},
    "new_user": {"username": f"testuser_{int(time.time())}", "password": "testpassword123"}
}

class APITester:
    def __init__(self):
        self.tokens = {}
        self.test_results = {
            "auth_login": False,
            "auth_register": False,
            "auth_me": False,
            "orders_list": False,
            "orders_detail": False,
            "init_sample_data": False
        }
        self.order_ids = []
        
    def print_separator(self):
        print("\n" + "="*80 + "\n")
        
    def print_test_header(self, test_name: str):
        self.print_separator()
        print(f"TESTING: {test_name}")
        print("-"*80)
        
    def print_response(self, response, include_headers=False):
        print(f"Status Code: {response.status_code}")
        if include_headers:
            print("Headers:")
            for key, value in response.headers.items():
                print(f"  {key}: {value}")
        
        try:
            print("Response Body:")
            print(json.dumps(response.json(), indent=2))
        except:
            print(f"Raw Response: {response.text}")
            
    def make_request(self, method: str, endpoint: str, data: Optional[Dict[str, Any]] = None, 
                    token: Optional[str] = None, expected_status: Optional[int] = None) -> Tuple[requests.Response, bool]:
        url = f"{API_URL}{endpoint}"
        headers = {"Content-Type": "application/json"}
        
        if token:
            headers["Authorization"] = f"Bearer {token}"
            
        try:
            if method.lower() == "get":
                response = requests.get(url, headers=headers)
            elif method.lower() == "post":
                response = requests.post(url, json=data, headers=headers)
            elif method.lower() == "put":
                response = requests.put(url, json=data, headers=headers)
            elif method.lower() == "delete":
                response = requests.delete(url, headers=headers)
            else:
                print(f"Unsupported method: {method}")
                return None, False
                
            success = True
            if expected_status and response.status_code != expected_status:
                print(f"❌ Expected status {expected_status}, got {response.status_code}")
                success = False
                
            return response, success
            
        except Exception as e:
            print(f"❌ Request failed: {e}")
            return None, False
            
    def test_init_sample_data(self):
        self.print_test_header("Sample Data Initialization")
        
        response, success = self.make_request("post", "/init-sample-data", expected_status=200)
        if response:
            self.print_response(response)
            if success and response.status_code == 200:
                print("✅ Sample data initialization successful")
                self.test_results["init_sample_data"] = True
            else:
                print("❌ Sample data initialization failed")
        
    def test_login(self, user_type: str) -> Optional[str]:
        credentials = TEST_CREDENTIALS.get(user_type)
        if not credentials:
            print(f"❌ Unknown user type: {user_type}")
            return None
            
        self.print_test_header(f"Login ({user_type})")
        
        response, success = self.make_request("post", "/login", data=credentials)
        self.print_response(response)
        
        if not response:
            return None
            
        if user_type.startswith("valid_") or user_type == "new_user":
            expected_status = 200
            if response.status_code == expected_status:
                try:
                    token = response.json().get("access_token")
                    if token:
                        print(f"✅ Login successful for {user_type}")
                        self.tokens[user_type] = token
                        if not self.test_results["auth_login"]:
                            self.test_results["auth_login"] = True
                        return token
                    else:
                        print("❌ No access token in response")
                except:
                    print("❌ Invalid JSON response")
            else:
                print(f"❌ Login failed for {user_type} with status {response.status_code}")
        else:
            # For invalid users, we expect a 401 status
            if response.status_code == 401:
                print(f"✅ Login correctly rejected for {user_type}")
            else:
                print(f"❌ Expected 401 for invalid login, got {response.status_code}")
                
        return None
        
    def test_register(self):
        self.print_test_header("User Registration")
        
        # Test registering a new user
        new_user = TEST_CREDENTIALS["new_user"]
        response, success = self.make_request("post", "/register", data=new_user, expected_status=200)
        
        if response:
            self.print_response(response)
            if success and response.status_code == 200:
                print(f"✅ Registration successful for {new_user['username']}")
                self.test_results["auth_register"] = True
                
                # Try to register the same user again (should fail)
                self.print_test_header("User Registration (Duplicate)")
                response, _ = self.make_request("post", "/register", data=new_user, expected_status=400)
                self.print_response(response)
                
                if response and response.status_code == 400:
                    print("✅ Duplicate registration correctly rejected")
                else:
                    print("❌ Duplicate registration should be rejected")
            else:
                print("❌ Registration failed")
        
        # Now try to login with the newly registered user
        token = self.test_login("new_user")
        return token is not None
        
    def test_current_user(self, user_type: str):
        self.print_test_header(f"Current User Info ({user_type})")
        
        token = self.tokens.get(user_type)
        if not token:
            print(f"❌ No token available for {user_type}")
            return False
            
        response, success = self.make_request("get", "/me", token=token, expected_status=200)
        
        if response:
            self.print_response(response)
            if success and response.status_code == 200:
                print(f"✅ Current user info retrieved successfully for {user_type}")
                self.test_results["auth_me"] = True
                return True
            else:
                print(f"❌ Failed to retrieve current user info for {user_type}")
                
        return False
        
    def test_invalid_token(self):
        self.print_test_header("Invalid Token Test")
        
        invalid_token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJpbnZhbGlkdXNlciIsImV4cCI6MTcxNjIzOTAyMn0.invalid_signature"
        response, _ = self.make_request("get", "/me", token=invalid_token, expected_status=401)
        
        if response:
            self.print_response(response)
            if response.status_code == 401:
                print("✅ Invalid token correctly rejected")
                return True
            else:
                print(f"❌ Expected 401 for invalid token, got {response.status_code}")
                
        return False
        
    def test_get_orders(self, user_type: str):
        self.print_test_header(f"Get Orders ({user_type})")
        
        token = self.tokens.get(user_type)
        if not token:
            print(f"❌ No token available for {user_type}")
            return False
            
        response, success = self.make_request("get", "/orders", token=token, expected_status=200)
        
        if response:
            self.print_response(response)
            if success and response.status_code == 200:
                try:
                    orders = response.json()
                    if isinstance(orders, list):
                        print(f"✅ Retrieved {len(orders)} orders for {user_type}")
                        self.test_results["orders_list"] = True
                        
                        # Store order IDs for detailed testing
                        if orders:
                            self.order_ids = [order.get("order_id") for order in orders if order.get("order_id")]
                            return True
                    else:
                        print("❌ Expected a list of orders")
                except:
                    print("❌ Invalid JSON response")
            else:
                print(f"❌ Failed to retrieve orders for {user_type}")
                
        return False
        
    def test_get_order_detail(self, user_type: str):
        self.print_test_header(f"Get Order Detail ({user_type})")
        
        token = self.tokens.get(user_type)
        if not token:
            print(f"❌ No token available for {user_type}")
            return False
            
        if not self.order_ids:
            print("❌ No order IDs available for testing")
            return False
            
        order_id = self.order_ids[0]
        response, success = self.make_request("get", f"/orders/{order_id}", token=token, expected_status=200)
        
        if response:
            self.print_response(response)
            if success and response.status_code == 200:
                try:
                    order = response.json()
                    if order.get("order_id") == order_id:
                        print(f"✅ Retrieved order details for {order_id}")
                        self.test_results["orders_detail"] = True
                        return True
                    else:
                        print("❌ Order ID mismatch in response")
                except:
                    print("❌ Invalid JSON response")
            else:
                print(f"❌ Failed to retrieve order details for {order_id}")
                
        # Test with invalid order ID
        invalid_order_id = "INVALID-ORDER-ID"
        self.print_test_header(f"Get Invalid Order Detail ({user_type})")
        response, _ = self.make_request("get", f"/orders/{invalid_order_id}", token=token, expected_status=404)
        
        if response:
            self.print_response(response)
            if response.status_code == 404:
                print("✅ Invalid order ID correctly returns 404")
            else:
                print(f"❌ Expected 404 for invalid order ID, got {response.status_code}")
                
        return False
        
    def run_all_tests(self):
        print("\n\n" + "="*80)
        print("STARTING BACKEND API TESTS")
        print("="*80 + "\n")
        
        # Initialize sample data
        self.test_init_sample_data()
        
        # Test authentication
        self.test_login("valid_user1")
        self.test_login("valid_user2")
        self.test_login("invalid_user")
        
        # Test registration
        registration_success = self.test_register()
        
        # Test current user endpoint
        if "valid_user1" in self.tokens:
            self.test_current_user("valid_user1")
        
        # Test invalid token
        self.test_invalid_token()
        
        # Test orders endpoints
        if "valid_user1" in self.tokens:
            self.test_get_orders("valid_user1")
            self.test_get_order_detail("valid_user1")
        
        # Print summary
        self.print_test_summary()
        
    def print_test_summary(self):
        self.print_separator()
        print("TEST SUMMARY")
        print("-"*80)
        
        all_passed = True
        for test_name, result in self.test_results.items():
            status = "✅ PASS" if result else "❌ FAIL"
            if not result:
                all_passed = False
            print(f"{test_name}: {status}")
            
        self.print_separator()
        if all_passed:
            print("🎉 ALL TESTS PASSED! 🎉")
        else:
            print("❌ SOME TESTS FAILED")
            
        self.print_separator()
        
if __name__ == "__main__":
    tester = APITester()
    tester.run_all_tests()