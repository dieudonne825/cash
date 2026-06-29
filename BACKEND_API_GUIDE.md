# Guide d'Intégration du Backend pour CashSMS

Ce guide fournit les structures de données JSON recommandées pour vos endpoints Laravel ou Django afin qu'ils s'intègrent parfaitement avec l'onglet **Gestion Clients** de l'application mobile CashSMS.

---

## 1. Modèle Client : Attributs Attendus

L'application CashSMS gère dynamiquement les clés du JSON retourné par le backend. Voici les attributs supportés :

| Champ dans CashSMS | Clés JSON supportées par l'application | Description |
| :--- | :--- | :--- |
| **ID** | `id` | Identifiant unique du client (numérique). |
| **Nom / Utilisateur** | `name`, `username` | Nom de l'utilisateur ou du client (prédominant dans l'UI). |
| **Téléphone** | `phone`, `phone_number` | Numéro de téléphone du client (ex: `+2250707070707` ou `+22997123456`). |
| **E-mail** | `email` | Adresse e-mail du client (optionnelle). |
| **Solde** | `balance`, `solde` | Solde actuel du compte en FCFA (numérique). |
| **Service de Paiement** | `service`, `payment_service`, `operator` | Identifiant du service mobile money lié (ex: `MTNMOMO`, `ORANGE`, `WAVE`, `MOOV`). |

---

## 2. Exemple d'Endpoint Laravel (PHP)

### Route Laravel (`routes/api.php`)
```php
use App\Http\Controllers\Api\ClientController;

Route::middleware('auth:sanctum')->get('/clients', [ClientController::class, 'index']);
```

### Contrôleur Laravel (`app/Http/Controllers/Api/ClientController.php`)
```php
<?php

namespace App\Http\Controllers\Api;

use App\Http\Controllers\Controller;
use App\Models\Client;
use Illuminate\Http\Request;

class ClientController extends Controller
{
    public function index()
    {
        // Récupérer les clients avec leurs soldes et services de paiement
        $clients = Client::all()->map(function($client) {
            return [
                'id'              => $client->id,
                'name'            => $client->username, // Votre colonne d'authentification ou nom
                'phone'           => $client->phone_number,
                'email'           => $client->email,
                'balance'         => (double) $client->solde,
                'payment_service' => $client->payment_operator, // ex: 'MTNMOMO', 'ORANGE', 'WAVE'
            ];
        });

        return response()->json($clients, 200);
    }
}
```

### JSON Renvoyé par Laravel (Format Attendu)
```json
[
  {
    "id": 1,
    "name": "Albert Essomba",
    "phone": "+24107123456",
    "email": "albert.e@gmail.com",
    "balance": 45000.0,
    "payment_service": "MTNMOMO"
  },
  {
    "id": 2,
    "name": "Sene Shop",
    "phone": "+2250707070707",
    "email": "sene.shop@gmail.com",
    "balance": 82450.0,
    "payment_service": "WAVE"
  },
  {
    "id": 3,
    "name": "Fatou Diop",
    "phone": "+221771234567",
    "email": "fatou.diop@wave.sn",
    "balance": 15400.0,
    "payment_service": "ORANGE"
  }
]
```

---

## 3. Exemple d'Endpoint Django (Python)

### Django REST Framework - Serializer (`serializers.py`)
```python
from rest_framework import serializers
from .models import Client

class ClientSerializer(serializers.ModelSerializer):
    # Mapping des champs spécifiques vers le format attendu
    name = serializers.CharField(source='user.username')
    phone = serializers.CharField(source='phone_number')
    balance = serializers.FloatField(source='solde')
    service = serializers.CharField(source='payment_operator')

    class Meta:
        model = Client
        fields = ['id', 'name', 'phone', 'email', 'balance', 'service']
```

### Django REST Framework - View (`views.py`)
```python
from rest_framework.views import APIView
from rest_framework.response import Response
from rest_framework.permissions import IsAuthenticated
from .models import Client
from .serializers import ClientSerializer

class ClientListView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        clients = Client.objects.all()
        serializer = ClientSerializer(clients, many=True)
        return Response(serializer.data)
```

### JSON Renvoyé par Django (Format Attendu)
```json
[
  {
    "id": 1,
    "name": "Jean-Pierre Kouamé",
    "phone": "+2250505050505",
    "email": "jp.kouame@gmail.com",
    "balance": 12000.0,
    "service": "ORANGE"
  },
  {
    "id": 2,
    "name": "Mamadou Diallo",
    "phone": "+224621112233",
    "email": "diallo.momo@gmail.com",
    "balance": 32000.0,
    "service": "MOOV"
  }
]
```

---

## 4. Comment Tester Votre Configuration

1. Allez dans l'onglet **Paramètres** de CashSMS.
2. Saisissez votre **URL de base** (ex: `https://votre-domaine.com` ou l'adresse IP de votre serveur local de développement).
3. Renseignez votre **Token d'authentification API** (Token Laravel Sanctum ou Django Token/JWT).
4. Sélectionnez le type de backend (**Laravel** ou **Django**).
5. Retournez sur l'onglet **Envoyer** (Gestion Clients) : l'application va automatiquement interroger votre endpoint pour obtenir les données réelles et effectuer les réconciliations de dépôts !
