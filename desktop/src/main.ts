import { invoke } from "@tauri-apps/api/core";

// Removed unused types

let allItems: any[] = [];

function getIconForType(type: string): string {
  if (type.includes('Login')) return '<i class="fa-solid fa-key"></i>';
  if (type.includes('CreditCard')) return '<i class="fa-solid fa-credit-card"></i>';
  return '<i class="fa-solid fa-file-shield"></i>';
}

function getIconClassForType(type: string): string {
  if (type.includes('Login')) return 'password';
  if (type.includes('CreditCard')) return 'card';
  return 'note';
}

function renderItems(items: any[]) {
  const grid = document.getElementById('items-grid');
  if (!grid) return;
  
  grid.innerHTML = '';
  
  items.forEach(item => {
    // The Rust enum `EntryType` comes through as a string or object in JS
    const typeStr = JSON.stringify(item.entry_type);
    
    const card = document.createElement('div');
    card.className = 'vault-card';
    card.innerHTML = `
      <div class="card-header">
        <div class="card-icon ${getIconClassForType(typeStr)}">
          ${getIconForType(typeStr)}
        </div>
        <div>
          <div class="card-title">${item.title}</div>
          <div class="card-subtitle">Local Vault &bull; Just now</div>
        </div>
      </div>
      <div class="card-actions">
        <button class="action-btn" title="Copy Username"><i class="fa-regular fa-user"></i></button>
        <button class="action-btn" title="Copy Password"><i class="fa-solid fa-copy"></i></button>
        <button class="action-btn" title="View Options"><i class="fa-solid fa-ellipsis-vertical"></i></button>
      </div>
    `;
    grid.appendChild(card);
  });
}

let currentFilter = 'all';

// Search functionality
function setupSearch() {
  const searchInput = document.getElementById('search-input') as HTMLInputElement;
  if (!searchInput) return;

  searchInput.addEventListener('input', () => {
    applyFilters();
  });
}

function applyFilters() {
  const searchInput = document.getElementById('search-input') as HTMLInputElement;
  const term = searchInput ? searchInput.value.toLowerCase() : '';
  
  let filtered = allItems;
  
  if (currentFilter !== 'all') {
    filtered = filtered.filter(item => JSON.stringify(item.entry_type).includes(currentFilter));
  }
  
  if (term) {
    filtered = filtered.filter(item => item.title.toLowerCase().includes(term));
  }
  
  const contentHeader = document.querySelector('.content-header h2');
  if (contentHeader) {
      if (currentFilter === 'all') contentHeader.textContent = 'All Items';
      else if (currentFilter === 'Login') contentHeader.textContent = 'Passwords';
      else if (currentFilter === 'CreditCard') contentHeader.textContent = 'Payment Cards';
      else if (currentFilter === 'SecureNote') contentHeader.textContent = 'Secure Notes';
  }
  
  renderItems(filtered);
}

function setupNavigation() {
  const navItems = document.querySelectorAll('.nav-item');
  navItems.forEach(item => {
    item.addEventListener('click', (e) => {
      e.preventDefault();
      
      navItems.forEach(n => n.classList.remove('active'));
      item.classList.add('active');
      
      const filter = item.getAttribute('data-filter');
      const tool = item.getAttribute('data-tool');
      
      if (filter) {
        currentFilter = filter;
        applyFilters();
      } else if (tool) {
        const grid = document.getElementById('items-grid');
        const contentHeader = document.querySelector('.content-header h2');
        if (grid && contentHeader) {
            contentHeader.textContent = tool === 'totp' ? 'Authenticator' : 'Generator';
            grid.innerHTML = `
              <div style="grid-column: 1/-1; text-align: center; padding: 60px; color: var(--text-secondary);">
                <i class="fa-solid fa-screwdriver-wrench" style="font-size: 3rem; margin-bottom: 20px; color: var(--accent-primary);"></i>
                <h3 style="color: var(--text-primary); margin-bottom: 10px;">Coming Soon</h3>
                <p>This feature will be available in the next update.</p>
              </div>
            `;
        }
      }
    });
  });
}

async function fetchEntries() {
  try {
    const apiKey: string = await invoke("get_api_key");
    const response = await fetch("http://127.0.0.1:8023/api/v1/vault/entries", {
      headers: {
        "Authorization": `Bearer ${apiKey}`
      }
    });
    
    if (response.ok) {
      const entries = await response.json();
      allItems = entries;
      applyFilters();
    } else {
      throw new Error(`HTTP Error: ${response.status}`);
    }
  } catch (e) {
    console.error("Failed to fetch entries", e);
    // Remove the mock fallback entirely as requested by "Remove Mock UI Logic"
    allItems = [];
    applyFilters();
  }
}

function setupLogin() {
  const loginForm = document.getElementById('login-form') as HTMLFormElement;
  const loginError = document.getElementById('login-error') as HTMLDivElement;
  
  if (!loginForm) return;

  loginForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    loginError.style.display = 'none';
    const passwordInput = document.getElementById('master-password') as HTMLInputElement;
    const password = passwordInput.value;

    try {
      const apiKey: string = await invoke("get_api_key");
      const response = await fetch("http://127.0.0.1:8023/api/v1/vault/unlock", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${apiKey}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({ password })
      });
      
      if (response.ok) {
        document.getElementById('login-overlay')!.style.display = 'none';
        document.getElementById('app')!.style.display = 'flex';
        fetchEntries();
      } else {
        loginError.textContent = "Invalid Master Password.";
        loginError.style.display = 'block';
      }
    } catch (err) {
      console.error("Login error", err);
      loginError.textContent = "Could not connect to Fortis Agent.";
      loginError.style.display = 'block';
    }
  });
}

// Initialize app UI
window.addEventListener("DOMContentLoaded", () => {
  setupLogin();
  setupSearch();
  setupNavigation();
  
  // Modal Logic
  const addBtn = document.getElementById('add-btn');
  const modal = document.getElementById('add-modal');
  const closeBtn = document.getElementById('close-modal-btn');
  const cancelBtn = document.getElementById('cancel-add-btn');
  const form = document.getElementById('add-item-form') as HTMLFormElement;
  const typeSelect = document.getElementById('item-type') as HTMLSelectElement;

  function updateDynamicFields() {
    document.getElementById('dynamic-fields-login')!.style.display = 'none';
    document.getElementById('dynamic-fields-card')!.style.display = 'none';
    document.getElementById('dynamic-fields-totp')!.style.display = 'none';
    
    if (typeSelect.value === 'Login') {
      document.getElementById('dynamic-fields-login')!.style.display = 'block';
    } else if (typeSelect.value === 'CreditCard') {
      document.getElementById('dynamic-fields-card')!.style.display = 'block';
    } else if (typeSelect.value === 'Authenticator') {
      document.getElementById('dynamic-fields-totp')!.style.display = 'block';
    }
  }

  typeSelect?.addEventListener('change', updateDynamicFields);

  function openModal() {
    modal?.classList.remove('hidden');
    updateDynamicFields();
  }

  function closeModal() {
    modal?.classList.add('hidden');
    form?.reset();
    updateDynamicFields();
  }

  addBtn?.addEventListener('click', openModal);
  closeBtn?.addEventListener('click', closeModal);
  cancelBtn?.addEventListener('click', closeModal);

  form?.addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const titleInput = document.getElementById('item-title') as HTMLInputElement;
    const notesInput = document.getElementById('item-notes') as HTMLTextAreaElement;
    
    let entryType = typeSelect.value;
    // Map Authenticator to Login in the backend to persist totp_secret
    if (entryType === 'Authenticator') entryType = 'Login';

    const newItem: any = {
      title: titleInput.value,
      notes: notesInput.value,
      entry_type: entryType,
    };
    
    if (typeSelect.value === 'Login') {
      newItem.username = (document.getElementById('item-username') as HTMLInputElement).value;
      newItem.password = (document.getElementById('item-password') as HTMLInputElement).value;
    } else if (typeSelect.value === 'Authenticator') {
      newItem.username = "";
      newItem.password = "";
      newItem.totp_secret = (document.getElementById('item-totp-secret') as HTMLInputElement).value.replace(/\s+/g, '');
    }
    
    try {
      const apiKey: string = await invoke("get_api_key");
      const response = await fetch("http://127.0.0.1:8023/api/v1/vault/entries", {
        method: "POST",
        headers: {
          "Authorization": `Bearer ${apiKey}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify(newItem)
      });
      
      if (response.ok) {
        closeModal();
        fetchEntries(); // Refresh list
      } else {
        alert("Failed to save item: HTTP " + response.status);
      }
    } catch (err) {
      console.error("Error saving item", err);
      alert("Error saving item to unified agent.");
    }
  });
});
