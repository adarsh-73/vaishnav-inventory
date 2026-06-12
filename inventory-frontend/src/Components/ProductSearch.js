import React, { useState } from "react";

const dummyProducts = [
  { id: 1, name: "Car Wash Shampoo", price: 120, stock: 10 },
  { id: 2, name: "Microfiber Cloth", price: 50, stock: 0 },
  { id: 3, name: "Car Polish", price: 250, stock: 5 }
];

function ProductSearch({ onSelect }) {
  const [query, setQuery] = useState("");
  const [result, setResult] = useState([]);

  const handleSearch = (value) => {
    setQuery(value);

    const filtered = dummyProducts.filter((p) =>
      p.name.toLowerCase().includes(value.toLowerCase())
    );

    setResult(filtered);
  };

  return (
    <div>
      <input
        placeholder="Product search..."
        value={query}
        onChange={(e) => handleSearch(e.target.value)}
      />

      <div>
        {result.map((item) => (
          <div
            key={item.id}
            onClick={() => onSelect(item)}
            style={{ cursor: "pointer", margin: "5px 0" }}
          >
            {item.name} - ₹{item.price}{" "}
            {item.stock > 0 ? "✅ Available" : "❌ Out of stock"}
          </div>
        ))}
      </div>
    </div>
  );
}

export default ProductSearch;